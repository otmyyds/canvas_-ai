package com.picturecreate.doccanvas.storage;

import java.io.IOException;
import java.io.InputStream;

public interface ObjectStorageService {

    StoredObject put(String key, String contentType, long size, InputStream inputStream) throws IOException;

    StoredObjectContent get(String key);

    void delete(String key);

    record StoredObject(String bucket, String key, String contentType, long size) {
    }

    record StoredObjectContent(String contentType, byte[] bytes) {
    }
}
