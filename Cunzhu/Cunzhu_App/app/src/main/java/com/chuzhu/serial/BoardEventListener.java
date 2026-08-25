package com.chuzhu.serial;

public interface BoardEventListener {
    void onBoardEvent(BoardEvent event);

    void onSerialError(String message, Throwable error);
}
