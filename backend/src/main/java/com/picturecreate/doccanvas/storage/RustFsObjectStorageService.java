package com.picturecreate.doccanvas.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.picturecreate.doccanvas.config.AppProperties;

import jakarta.annotation.PostConstruct;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@ConditionalOnProperty(name = "app.storage.type", havingValue = "s3", matchIfMissing = true)
public class RustFsObjectStorageService implements ObjectStorageService {

    private final AppProperties properties;
    private final S3Client s3Client;

    public RustFsObjectStorageService(AppProperties properties) {
        this.properties = properties;
        AppProperties.RustFs rustfs = properties.rustfs();
        this.s3Client = S3Client.builder()
                .endpointOverride(URI.create(rustfs.endpoint()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(rustfs.accessKey(), rustfs.secretKey())))
                .region(Region.of(rustfs.region()))
                .forcePathStyle(true)
                .build();
    }

    @PostConstruct
    void ensureBucket() {
        String bucket = properties.rustfs().bucket();
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException exception) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (RuntimeException exception) {
            // 本地开发时后端可能早于 RustFS 启动，真正上传时会返回明确错误。
        }
    }

    @Override
    public StoredObject put(String key, String contentType, long size, InputStream inputStream) throws IOException {
        String bucket = properties.rustfs().bucket();
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType(contentType)
                .contentLength(size)
                .build();
        s3Client.putObject(request, RequestBody.fromInputStream(inputStream, size));
        return new StoredObject(bucket, key, contentType, size);
    }

    @Override
    public StoredObjectContent get(String key) {
        String bucket = properties.rustfs().bucket();
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        ResponseBytes<GetObjectResponse> response = s3Client.getObjectAsBytes(request);
        String contentType = response.response().contentType() == null
                ? "application/octet-stream"
                : response.response().contentType();
        return new StoredObjectContent(contentType, response.asByteArray());
    }

    @Override
    public void delete(String key) {
        String bucket = properties.rustfs().bucket();
        s3Client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public void deletePrefix(String prefix) {
        deletePrefixExcept(prefix, null, null);
    }

    @Override
    public void deletePrefixExcept(String prefix, String keepKey) {
        deletePrefixExcept(prefix, keepKey, null);
    }

    @Override
    public void deletePrefixExcept(String prefix, String keepKey, Instant olderThan) {
        String bucket = properties.rustfs().bucket();
        String continuationToken = null;
        do {
            ListObjectsV2Response page = s3Client.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .continuationToken(continuationToken)
                    .build());
            List<ObjectIdentifier> objects = page.contents().stream()
                    .filter(object -> keepKey == null || !object.key().equals(keepKey))
                    .filter(object -> olderThan == null
                            || (object.lastModified() != null && object.lastModified().isBefore(olderThan)))
                    .map(object -> ObjectIdentifier.builder().key(object.key()).build())
                    .toList();
            if (!objects.isEmpty()) {
                s3Client.deleteObjects(DeleteObjectsRequest.builder()
                        .bucket(bucket)
                        .delete(Delete.builder().objects(objects).build())
                        .build());
            }
            continuationToken = Boolean.TRUE.equals(page.isTruncated())
                    ? page.nextContinuationToken()
                    : null;
        } while (continuationToken != null);
    }
}
