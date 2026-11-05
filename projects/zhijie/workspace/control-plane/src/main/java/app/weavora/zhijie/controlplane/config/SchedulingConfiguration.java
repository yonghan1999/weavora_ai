package app.weavora.zhijie.controlplane.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@ConditionalOnProperty(name = "zhijie.scheduling.enabled", matchIfMissing = true)
@EnableScheduling
public class SchedulingConfiguration {
}
