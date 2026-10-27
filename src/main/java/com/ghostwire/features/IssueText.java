package com.ghostwire.features;

/** Turns Burp's HTML issue text into plain text for a prompt. */
public final class IssueText {

    private IssueText() {}

    public static String plain(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n");
        text = text.replaceAll("<[^>]+>", " ");
        text = text.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");
        return text.replaceAll("[ \\t]+", " ").replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").trim();
    }
}
