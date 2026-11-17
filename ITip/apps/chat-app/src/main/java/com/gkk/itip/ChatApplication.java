package com.gkk.itip;

import com.gkk.itip.common.auth.SupabaseAuthConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(SupabaseAuthConfiguration.class)
public class ChatApplication {
    public static void main(String[] args) { SpringApplication.run(ChatApplication.class, args); }
}
