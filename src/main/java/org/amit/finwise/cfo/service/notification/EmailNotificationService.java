package org.amit.finwise.cfo.service.notification;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.amit.finwise.cfo.model.AiInsight;
import org.amit.finwise.cfo.model.PortfolioSnapshot;
import org.amit.finwise.cfo.repository.AiInsightRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.mail.internet.MimeMessage;
import java.time.format.DateTimeFormatter;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailNotificationService {

    private final JavaMailSender mailSender;
    private final AiInsightRepository insightRepository;

    @Value("${spring.mail.username}")
    private String fromEmail;

    @Value("${cfo.user.email:}")
    private String fallbackEmail;

    /**
     * Send the daily CFO brief as an HTML email.
     */
    @Transactional
    public void sendDailyBrief(AiInsight insight, String recipientEmail) {
        if (insight == null) {
            log.warn("No daily brief to send");
            return;
        }
        if (Boolean.TRUE.equals(insight.getEmailed())) {
            log.debug("Daily brief already emailed");
            return;
        }
        String to = resolveRecipient(recipientEmail);
        if (to == null) return;

        String subject = "Your CFO Daily Brief — " +
                insight.getInsightDate().format(DateTimeFormatter.ofPattern("dd MMM yyyy"));
        String htmlBody = buildEmailHtml(insight);

        sendHtmlEmail(to, subject, htmlBody);

        insight.setEmailed(true);
        insightRepository.save(insight);
        log.info("Daily brief email sent to {}", to);
    }

    /**
     * Send an after-hours portfolio summary email.
     */
    @Transactional
    public void sendAfterHoursInsight(AiInsight insight, PortfolioSnapshot snapshot, String recipientEmail) {
        if (insight == null || Boolean.TRUE.equals(insight.getEmailed())) return;
        String to = resolveRecipient(recipientEmail);
        if (to == null) return;

        String portfolioSummary = buildPortfolioSummaryHtml(snapshot);

        String subject = "CFO After-Hours Review — " +
                insight.getInsightDate().format(DateTimeFormatter.ofPattern("dd MMM yyyy"));
        String htmlBody = buildEmailHtml(insight, portfolioSummary);

        sendHtmlEmail(to, subject, htmlBody);

        insight.setEmailed(true);
        insightRepository.save(insight);
        log.info("After-hours insight email sent to {}", to);
    }

    private static String buildPortfolioSummaryHtml(PortfolioSnapshot snapshot) {
        if (snapshot == null) return "";
        return """
                <div style="background:#f4f6f8;padding:12px 16px;border-radius:6px;margin-bottom:16px;font-family:monospace;">
                <strong>Portfolio Value:</strong> ₹%s &nbsp;|&nbsp;
                <strong>Day P&L:</strong> <span style="color:%s;">₹%s (%s%%)</span>
                </div>
                """.formatted(
                snapshot.getCurrentValue(),
                snapshot.getDayPnl() != null && snapshot.getDayPnl().signum() >= 0 ? "#1a7c3e" : "#c0392b",
                snapshot.getDayPnl(),
                snapshot.getDayPnlPercent()
        );
    }

    /**
     * Send a portfolio alert (e.g., major drop detected).
     */
    public void sendPortfolioAlert(String alertTitle, String alertBody, String recipientEmail) {
        String to = resolveRecipient(recipientEmail);
        if (to == null) return;
        String subject = "CFO Alert: " + alertTitle;
        String html = buildEmailHtml(alertTitle, alertBody);
        sendHtmlEmail(to, subject, html);
        log.info("Portfolio alert email sent: {}", alertTitle);
    }

    private String resolveRecipient(String recipientEmail) {
        if (recipientEmail != null && !recipientEmail.isBlank()) return recipientEmail;
        if (fallbackEmail != null && !fallbackEmail.isBlank()) return fallbackEmail;
        log.warn("No recipient email configured; skipping email send");
        return null;
    }

    // ── HTML Templates ────────────────────────────────────────────────────────

    private String buildEmailHtml(AiInsight insight) {
        return buildEmailHtml(insight, "");
    }

    private String buildEmailHtml(AiInsight insight, String extraHtml) {
        String htmlContent = markdownToBasicHtml(insight.getContent());
        return wrapInEmailLayout(insight.getTitle(), extraHtml + htmlContent);
    }

    private String buildEmailHtml(String title, String markdownBody) {
        return wrapInEmailLayout(title, markdownToBasicHtml(markdownBody));
    }

    private String wrapInEmailLayout(String title, String bodyContent) {
        return """
                <!DOCTYPE html>
                <html>
                <head>
                  <meta charset="UTF-8"/>
                  <style>
                    body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #f0f2f5; margin: 0; padding: 20px; color: #1a1a2e; }
                    .container { max-width: 680px; margin: 0 auto; background: #ffffff; border-radius: 10px; overflow: hidden; box-shadow: 0 2px 10px rgba(0,0,0,0.08); }
                    .header { background: linear-gradient(135deg, #1a1a2e 0%%, #16213e 100%%); color: white; padding: 24px 32px; }
                    .header h1 { margin: 0; font-size: 20px; font-weight: 600; }
                    .header p { margin: 4px 0 0; opacity: 0.7; font-size: 13px; }
                    .body { padding: 28px 32px; line-height: 1.7; }
                    h2 { color: #1a1a2e; border-bottom: 2px solid #e8eaf0; padding-bottom: 6px; margin-top: 24px; font-size: 16px; }
                    h3 { color: #2c3e50; font-size: 14px; margin-top: 16px; }
                    ul { padding-left: 20px; }
                    li { margin-bottom: 6px; }
                    code { background: #f4f6f8; padding: 2px 6px; border-radius: 3px; font-size: 13px; }
                    strong { color: #1a1a2e; }
                    .footer { background: #f8f9fa; padding: 16px 32px; font-size: 12px; color: #888; border-top: 1px solid #e8eaf0; }
                  </style>
                </head>
                <body>
                  <div class="container">
                    <div class="header">
                      <h1>Your Personal CFO</h1>
                      <p>%s</p>
                    </div>
                    <div class="body">%s</div>
                    <div class="footer">Generated by your Personal CFO &mdash; Powered by AI</div>
                  </div>
                </body>
                </html>
                """.formatted(title, bodyContent);
    }

    private String markdownToBasicHtml(String md) {
        if (md == null) return "";
        return md
                .replaceAll("(?m)^### (.+)$", "<h3>$1</h3>")
                .replaceAll("(?m)^## (.+)$", "<h2>$1</h2>")
                .replaceAll("(?m)^# (.+)$", "<h2>$1</h2>")
                .replaceAll("\\*\\*(.+?)\\*\\*", "<strong>$1</strong>")
                .replaceAll("\\*(.+?)\\*", "<em>$1</em>")
                .replaceAll("`(.+?)`", "<code>$1</code>")
                .replaceAll("(?m)^- (.+)$", "<li>$1</li>")
                .replaceAll("(<li>.+</li>)(?!\\s*<li>)", "<ul>$1</ul>")
                .replaceAll("\n\n", "<br/><br/>")
                .replaceAll("\n", "<br/>");
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    private void sendHtmlEmail(String to, String subject, String html) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(html, true);
            mailSender.send(message);
        } catch (Exception e) {
            log.error("Failed to send email '{}': {}", subject, e.getMessage());
        }
    }
}
