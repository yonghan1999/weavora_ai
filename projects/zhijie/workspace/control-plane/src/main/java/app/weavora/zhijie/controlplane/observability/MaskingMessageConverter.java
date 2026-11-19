package app.weavora.zhijie.controlplane.observability;

import ch.qos.logback.classic.pattern.ThrowableHandlingConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

public class MaskingMessageConverter extends ThrowableHandlingConverter {
    @Override
    public String convert(ILoggingEvent event) {
        String message = LogSanitizer.sanitize(event.getFormattedMessage());
        var failure = event.getThrowableProxy();
        return failure == null ? message : message + " [exception=" + failure.getClassName() + "]";
    }
}
