package net.lamgc.cgj.bot.cache;

import net.lamgc.cgj.bot.boot.BotGlobal;
import net.lamgc.cgj.bot.cache.exception.HttpRequestException;
import net.lamgc.cgj.pixiv.PixivURL;
import net.lamgc.cgj.util.URLs;
import net.lamgc.utils.event.EventHandler;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.tomcat.util.http.fileupload.util.Streams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ImageCacheHandler implements EventHandler {

    private final static Logger log = LoggerFactory.getLogger(ImageCacheHandler.class);

    private final static HttpClient httpClient = HttpClientBuilder.create()
            .setProxy(BotGlobal.getGlobal().getProxy())
            .build();

    private final static Set<ImageCacheObject> cacheQueue = Collections.synchronizedSet(new HashSet<>());

    @SuppressWarnings("unused")
    public void getImageToCache(ImageCacheObject event) throws Exception {
        if(cacheQueue.contains(event)) {
            log.warn("图片 {} 已存在相同缓存任务, 跳过.", event.getStoreFile().getName());
            return;
        } else {
            cacheQueue.add(event);
        }

        try {
            log.debug("图片 {} Event正在进行...({})", event.getStoreFile().getName(), Integer.toHexString(event.hashCode()));
            File storeFile = event.getStoreFile();
            log.debug("正在缓存图片 {} (Path: {})", storeFile.getName(), storeFile.getAbsolutePath());
            try {
                if(!storeFile.exists() && !storeFile.createNewFile()) {
                    log.error("无法创建文件(Path: {})", storeFile.getAbsolutePath());
                    throw new IOException("Failed to create file");
                }
            } catch (IOException e) {
                log.error("无法创建文件(Path: {})", storeFile.getAbsolutePath());
                throw e;
            }

            HttpGet request = new HttpGet(event.getDownloadLink());
            request.addHeader("Referer", PixivURL.getPixivRefererLink(event.getIllustId()));
            HttpResponse response;
            try {
                response = httpClient.execute(request);
            } catch (IOException e) {
                log.error("Http请求时发生异常", e);
                throw e;
            }
            if(response.getStatusLine().getStatusCode() != 200) {
                HttpRequestException requestException = new HttpRequestException(response);
                log.warn("Http请求异常：{}", requestException.getStatusLine());
                throw requestException;
            }

            log.debug("正在下载...(Content-Length: {}KB)", response.getEntity().getContentLength() / 1024);
            ByteArrayOutputStream bufferOutputStream = new ByteArrayOutputStream();
            try(FileOutputStream fileOutputStream = new FileOutputStream(storeFile)) {
                Streams.copy(response.getEntity().getContent(), bufferOutputStream, false);
                ByteArrayInputStream bufferInputStream = new ByteArrayInputStream(bufferOutputStream.toByteArray());
                CacheStoreCentral.ImageChecksum imageChecksum = CacheStoreCentral.ImageChecksum
                        .buildImageChecksumFromStream(
                                event.getIllustId(),
                                event.getPageIndex(),
                                event.getStoreFile().getName(),
                                bufferInputStream
                            );
                bufferInputStream.reset();
                Streams.copy(bufferInputStream, fileOutputStream, false);
                CacheStoreCentral.getCentral().setImageChecksum(imageChecksum);
            } catch (IOException e) {
                log.error("下载图片时发生异常", e);
                throw e;
            }
            event.getImageCache().put(URLs.getResourceName(event.getDownloadLink()), storeFile);
        } finally {
            log.debug("图片 {} Event结束({})", event.getStoreFile().getName(), Integer.toHexString(event.hashCode()));
            cacheQueue.remove(event);
        }
    }

}
