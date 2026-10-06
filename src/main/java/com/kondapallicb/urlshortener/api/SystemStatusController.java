package com.kondapallicb.urlshortener.api;

import com.kondapallicb.urlshortener.observability.SystemStatus;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/system")
public class SystemStatusController {

    @GetMapping("/status")
    public SystemStatus status() {
        return new SystemStatus("UP", "url-shortener-agentic", Instant.now());
    }
}
