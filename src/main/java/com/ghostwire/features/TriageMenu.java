package com.ghostwire.features;

import burp.api.montoya.scanner.audit.issues.AuditIssue;
import burp.api.montoya.ui.contextmenu.AuditIssueContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import com.ghostwire.ui.ReportView;

import javax.swing.JMenuItem;
import java.awt.Component;
import java.util.List;

/** Right-click entry on scanner issues. */
public final class TriageMenu implements ContextMenuItemsProvider {

    private final ReportView report;

    public TriageMenu(ReportView report) {
        this.report = report;
    }

    @Override
    public List<Component> provideMenuItems(AuditIssueContextMenuEvent event) {
        List<AuditIssue> issues = event.selectedIssues();
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        JMenuItem item = new JMenuItem("Ghostwire: Triage issues");
        item.addActionListener(e -> report.triage(issues));
        return List.of(item);
    }
}
