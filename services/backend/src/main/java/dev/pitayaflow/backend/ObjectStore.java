package dev.pitayaflow.backend;

// Adapted from LinkVideo f5451c4, server/utils/MinioUtils.java (MIT, Copyright 2026 Majst): private bucket operations only.

import io.minio.*;
import io.minio.messages.Item;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import okhttp3.OkHttpClient;
import java.time.Duration;

@Component
final class ObjectStore {
    private final MinioClient minio;
    private final String bucket;
    ObjectStore(@Value("${pf.minio.endpoint}") String endpoint, @Value("${pf.minio.access-key}") String access,
                @Value("${pf.minio.secret-key}") String secret, @Value("${pf.minio.bucket}") String bucket) {
        OkHttpClient bounded = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(15)).writeTimeout(Duration.ofSeconds(15))
                .callTimeout(Duration.ofSeconds(30)).build();
        this.minio = MinioClient.builder().endpoint(endpoint).credentials(access, secret).httpClient(bounded).build(); this.bucket = bucket;
    }
    void put(String key, Path path) throws Exception {
        ensureBucket();
        try (InputStream input = Files.newInputStream(path)) {
            minio.putObject(PutObjectArgs.builder().bucket(bucket).object(key).stream(input, Files.size(path), -1)
                    .contentType("application/octet-stream").build());
        }
    }
    byte[] get(String key) throws Exception {
        try (InputStream input = minio.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            byte[] data = input.readNBytes(33 * 1024 * 1024 + 1);
            if (data.length > 33 * 1024 * 1024) throw new IllegalStateException("object too large");
            return data;
        }
    }
    void copyTo(String key,Path destination,long limit) throws Exception {
        try(InputStream input=minio.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build())) {
            try(var output=Files.newOutputStream(destination)) {
                byte[] buffer=new byte[8192];long total=0;
                for(int n;(n=input.read(buffer))!=-1;) {total+=n;if(total>limit)throw new IllegalStateException("object too large");output.write(buffer,0,n);}
            }
        }
    }
    InputStream openRange(String key,long offset,long length) throws Exception {
        return minio.getObject(GetObjectArgs.builder().bucket(bucket).object(key).offset(offset).length(length).build());
    }
    void remove(String key) throws Exception { minio.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build()); }
    List<String> keys() throws Exception {
        List<String> result = new ArrayList<>();
        for (var item : minio.listObjects(ListObjectsArgs.builder().bucket(bucket).prefix("media/").build())) {
            Item entry = item.get(); result.add(entry.objectName());
        }
        return result;
    }
    private synchronized void ensureBucket() throws Exception {
        if (!minio.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) minio.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
    }
}
