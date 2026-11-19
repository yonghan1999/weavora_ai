package app.weavora.zhijie.controlplane.identity;

import app.weavora.zhijie.controlplane.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** 邮件投递与认证事务分离；SMTP 错误不向客户端或普通日志暴露凭据和验证码。 */
@Component
@ConditionalOnProperty(name = "zhijie.mail.enabled", havingValue = "true")
public class SmtpEmailDispatchAdapter implements EmailDispatchPort {
    private final JavaMailSender sender;
    private final String from;

    public SmtpEmailDispatchAdapter(JavaMailSender sender, @Value("${zhijie.mail.from}") String from) {
        if (from == null || from.isBlank() || from.contains("\r") || from.contains("\n")) {
            throw new IllegalArgumentException("必须配置有效的邮件发件地址");
        }
        this.sender = sender;
        this.from = from;
    }

    @Override
    public boolean isAvailable() {
        return true;
    }

    @Override
    public void sendChallenge(String email, ChallengePurpose purpose, String code) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject(purpose == ChallengePurpose.VERIFY_EMAIL ? "织界：验证邮箱" : "织界：重置密码");
        message.setText("你的验证码是：" + code + "\n\n验证码 15 分钟内有效，仅可使用一次。"
                + "请勿向他人提供验证码。若非本人操作，请忽略此邮件。");
        try {
            sender.send(message);
        } catch (MailException exception) {
            // 不附带原始异常，避免 SMTP 服务端响应回显接收地址、认证或邮件正文。
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EMAIL_DELIVERY_UNAVAILABLE", "邮件投递失败，请稍后重试", true);
        }
    }
}
