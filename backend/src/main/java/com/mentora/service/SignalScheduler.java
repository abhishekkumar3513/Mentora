package com.mentora.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDate;

/** Re-evaluates every student periodically so time-based conditions (a deadline passing, a new day) raise signals without any manual action. */
@Component
public class SignalScheduler {
  private static final Logger log = LoggerFactory.getLogger(SignalScheduler.class);
  private final SupportSignalService signals;
  public SignalScheduler(SupportSignalService signals) { this.signals = signals; }

  @Scheduled(initialDelay = 30_000, fixedDelay = 300_000)
  public void run() {
    try { int n = signals.evaluateAll(LocalDate.now()); if (n > 0) log.info("Support signal evaluation created {} notification(s)", n); }
    catch (Exception e) { log.warn("Support signal evaluation failed: {}", e.toString()); }
  }
}
