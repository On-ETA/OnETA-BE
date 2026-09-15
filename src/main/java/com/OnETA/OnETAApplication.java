package com.OnETA;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
@org.springframework.context.annotation.Import(com.HomeRun.scheduler.NotificationScheduler.class)
public class OnETAApplication {

	public static void main(String[] args) {
		SpringApplication.run(OnETAApplication.class, args);
	}

}
