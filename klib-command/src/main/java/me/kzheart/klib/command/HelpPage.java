package me.kzheart.klib.command;

import me.kzheart.klib.lang.RichText;

public final class HelpPage {
    private final int page;
    private final int totalPages;
    private final RichText content;

    HelpPage(int page, int totalPages, RichText content) {
        this.page = page;
        this.totalPages = totalPages;
        this.content = content;
    }

    public int page() {
        return page;
    }

    public int totalPages() {
        return totalPages;
    }

    public RichText content() {
        return content;
    }
}
