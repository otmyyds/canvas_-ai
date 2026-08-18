package com.picturecreate.doccanvas.domain;

/**
 * 画布可见性。
 * 当前 MVP 仅 PRIVATE（单人私有）；SHARED_READONLY 为分享功能预留。
 */
public enum BoardVisibility {
    /** 私有（仅 owner 可见） */
    PRIVATE,
    /** 只读分享（预留） */
    SHARED_READONLY
}
