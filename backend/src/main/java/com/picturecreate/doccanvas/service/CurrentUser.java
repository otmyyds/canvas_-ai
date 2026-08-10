package com.picturecreate.doccanvas.service;

import java.util.UUID;

public record CurrentUser(UUID id, String email, String displayName) {
}
