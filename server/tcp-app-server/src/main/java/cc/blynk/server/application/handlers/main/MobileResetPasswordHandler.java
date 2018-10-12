package cc.blynk.server.application.handlers.main;

import cc.blynk.server.Holder;
import cc.blynk.server.core.BlockingIOProcessor;
import cc.blynk.server.core.dao.UserDao;
import cc.blynk.server.core.model.auth.User;
import cc.blynk.server.core.protocol.model.messages.appllication.ResetPasswordMessage;
import cc.blynk.server.internal.token.TokenUser;
import cc.blynk.server.internal.token.TokensPool;
import cc.blynk.server.notifications.mail.MailWrapper;
import cc.blynk.server.notifications.mail.QrHolder;
import cc.blynk.server.notifications.mail.ResetQrHolder;
import cc.blynk.utils.StringUtils;
import cc.blynk.utils.TokenGeneratorUtil;
import cc.blynk.utils.properties.Placeholders;
import cc.blynk.utils.validators.BlynkEmailValidator;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import net.glxn.qrgen.core.image.ImageType;
import net.glxn.qrgen.javase.QRCode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import static cc.blynk.server.internal.CommonByteBufUtil.illegalCommand;
import static cc.blynk.server.internal.CommonByteBufUtil.notAllowed;
import static cc.blynk.server.internal.CommonByteBufUtil.ok;
import static cc.blynk.server.internal.CommonByteBufUtil.serverError;

@ChannelHandler.Sharable
public class MobileResetPasswordHandler extends SimpleChannelInboundHandler<ResetPasswordMessage> {

    private static final Logger log = LogManager.getLogger(MobileResetPasswordHandler.class);

    private final TokensPool tokensPool;
    private final String resetEmailSubj;
    private final String resetEmailBody;
    private final String resetConfirmationSubj;
    private final String resetConfirmationBody;
    private final MailWrapper mailWrapper;
    private final UserDao userDao;
    private final BlockingIOProcessor blockingIOProcessor;
    private final String host;

    public MobileResetPasswordHandler(Holder holder) {
        this.tokensPool = holder.tokensPool;
        String productName = holder.props.productName;
        this.resetEmailSubj = "Password restoration for your " + productName + " account.";
        this.resetEmailBody = holder.textHolder.appResetEmailTemplate
                .replace(Placeholders.PRODUCT_NAME, productName);
        this.resetConfirmationSubj = "Your new password on " + productName;
        this.resetConfirmationBody = holder.textHolder.appResetEmailConfirmationTemplate
                .replace(Placeholders.PRODUCT_NAME, productName);
        this.mailWrapper = holder.mailWrapper;
        this.userDao = holder.userDao;
        this.blockingIOProcessor = holder.blockingIOProcessor;
        this.host = holder.props.getRestoreHost();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ResetPasswordMessage message) {
        String[] messageParts = message.body.split(StringUtils.BODY_SEPARATOR_STRING);

        switch (messageParts[0]) {
            case "start" :
                if (messageParts.length < 3) {
                    log.debug("Wrong income message format.");
                    ctx.writeAndFlush(illegalCommand(message.id), ctx.voidPromise());
                    return;
                }
                sendResetEMail(ctx, messageParts[1], messageParts[2], message.id);
                break;
            case "verify" :
                if (messageParts.length < 2) {
                    log.debug("Wrong income message format.");
                    ctx.writeAndFlush(illegalCommand(message.id), ctx.voidPromise());
                    return;
                }
                verifyToken(ctx, messageParts[1], message.id);
                break;
            case "reset" :
                if (messageParts.length < 3) {
                    log.debug("Wrong income message format.");
                    ctx.writeAndFlush(illegalCommand(message.id), ctx.voidPromise());
                    return;
                }
                reset(ctx, messageParts[1], messageParts[2], message.id);
                break;
        }
    }

    private void reset(ChannelHandlerContext ctx, String token, String passHash, int msgId) {
        TokenUser tokenUser = tokensPool.getUser(token);
        if (tokenUser == null) {
            log.warn("Invalid token for reset pass {}", token);
            ctx.writeAndFlush(notAllowed(msgId), ctx.voidPromise());
        } else {
            String email = tokenUser.email;
            User user = userDao.getByName(email, tokenUser.appName);
            if (user == null) {
                log.warn("User is not exists anymore. {}", tokenUser);
                ctx.writeAndFlush(serverError(msgId), ctx.voidPromise());
                return;
            }
            user.resetPass(passHash);
            tokensPool.removeToken(token);
            blockingIOProcessor.execute(() -> {
                try {
                    mailWrapper.sendHtml(email, resetConfirmationSubj,
                            resetConfirmationBody.replace(Placeholders.EMAIL, email));
                    log.debug("Confirmation {} mail sent.", email);
                } catch (Exception e) {
                    log.error("Error sending confirmation mail for {}. Reason : {}", email, e.getMessage());
                }
            });
            ctx.writeAndFlush(ok(msgId), ctx.voidPromise());
        }
    }

    private void verifyToken(ChannelHandlerContext ctx, String token, int msgId) {
        TokenUser tokenUser = tokensPool.getUser(token);
        if (tokenUser == null) {
            log.warn("Invalid token for reset pass {}", token);
            ctx.writeAndFlush(notAllowed(msgId), ctx.voidPromise());
        } else {
            ctx.writeAndFlush(ok(msgId), ctx.voidPromise());
        }
    }

    private void sendResetEMail(ChannelHandlerContext ctx, String inEMail, String appName, int msgId) {
        String trimmedEmail = inEMail.trim().toLowerCase();

        if (BlynkEmailValidator.isNotValidEmail(trimmedEmail)) {
            log.debug("Wrong income email for reset pass.");
            ctx.writeAndFlush(illegalCommand(msgId), ctx.voidPromise());
            return;
        }

        User user = userDao.getByName(trimmedEmail, appName);

        if (user == null) {
            log.debug("User does not exists.");
            ctx.writeAndFlush(illegalCommand(msgId), ctx.voidPromise());
            return;
        }

        if (tokensPool.hasToken(trimmedEmail, appName)) {
            tokensPool.cleanupOldTokens();
            log.warn("Reset code was already generated.");
            ctx.writeAndFlush(notAllowed(msgId), ctx.voidPromise());
            return;
        }

        String token = TokenGeneratorUtil.generateNewToken();
        log.info("{} trying to reset pass.", trimmedEmail);

        TokenUser userToken = new TokenUser(trimmedEmail, appName);
        tokensPool.addToken(token, userToken);

        String resetUrl = "http://" + host + "/restore?token=" + token + "&email=" + trimmedEmail;
        String body = resetEmailBody.replace(Placeholders.RESET_URL, resetUrl);
        String qrString = appName.toLowerCase() + "://restore?token=" + token + "&email=" + trimmedEmail;
        byte[] qrBytes = QRCode.from(qrString).to(ImageType.JPG).withSize(250, 250).stream().toByteArray();
        QrHolder qrHolder = new ResetQrHolder("resetPassQr.jpg", qrBytes);

        blockingIOProcessor.execute(() -> {
            try {
                mailWrapper.sendWithAttachment(trimmedEmail, resetEmailSubj, body, qrHolder);
                log.debug("{} mail sent.", trimmedEmail);
                ctx.writeAndFlush(ok(msgId), ctx.voidPromise());
            } catch (Exception e) {
                log.error("Error sending mail for {}. Reason : {}", trimmedEmail, e.getMessage());
                ctx.writeAndFlush(serverError(msgId), ctx.voidPromise());
            }
        });
    }
}
