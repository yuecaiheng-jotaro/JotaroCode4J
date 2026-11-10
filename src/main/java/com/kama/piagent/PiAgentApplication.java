package com.kama.piagent;

import com.kama.piagent.config.PiAgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.Arrays;

@SpringBootApplication
@EnableConfigurationProperties(PiAgentProperties.class)
public class PiAgentApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(PiAgentApplication.class);
        if (tuiRequested(args)) {
            application.setWebApplicationType(WebApplicationType.NONE);
            args = ensureTuiMode(args);
        }
        application.run(args);
    }

    static String[] ensureTuiMode(String[] args) {
        for (String arg : args) {
            if ("--piagent.mode=tui".equals(arg)) {
                return args;
            }
        }
        String[] updated = Arrays.copyOf(args, args.length + 1);
        updated[args.length] = "--piagent.mode=tui";
        return updated;
    }

    private static boolean tuiRequested(String[] args) {
        for (String arg : args) {
            if ("--tui".equals(arg) || "--piagent.mode=tui".equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
