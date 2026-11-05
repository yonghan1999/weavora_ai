package app.weavora.zhijie.controlplane.observability;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

public class MaskingMessageConverter extends ClassicConverter {
    @Override
    public String convert(ILoggingEvent event) {
        return LogSanitizer.sanitize(event.getFormattedMessage());
    }
}
