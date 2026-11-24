package com.picturecreate.doccanvas.storage;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;

public interface ObjectStorageService {

    StoredObject put(String key, String contentType, long size, InputStream inputStream) throws IOException;

    StoredObjectContent get(String key);

    void delete(String key);

    /** 删除某个前缀下的全部对象（用于删除画布时清理所有历史封面和图片） */
    void deletePrefix(String prefix);

    /** 删除某个前缀下除 keepKey 以外的对象（用于覆盖封面时清理旧版本） */
    void deletePrefixExcept(String prefix, String keepKey);

    /**
     * 删除某个前缀下除 keepKey 以外、且最后修改时间早于 olderThan 的对象。
     * 带时间水位是为了让"刚写入、事务还没提交"的对象不被另一路并发请求误删。
     */
    void deletePrefixExcept(String prefix, String keepKey, Instant olderThan);

    record StoredObject(String bucket, String key, String contentType, long size) {
    }

    record StoredObjectContent(String contentType, byte[] bytes) {
    }
}
