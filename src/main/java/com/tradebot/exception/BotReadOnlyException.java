package com.tradebot.exception;

public class BotReadOnlyException extends IllegalStateException {

    public BotReadOnlyException() {
        super("Trading is disabled. Bot is in READ-ONLY mode.");
    }
}
