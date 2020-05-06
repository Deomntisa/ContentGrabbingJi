package net.lamgc.cgj.bot.cache;

import net.lamgc.cgj.Main;
import net.lamgc.cgj.pixiv.PixivURL;
import net.lamgc.cgj.util.URLs;
import net.lamgc.utils.event.EventHandler;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class ImageCacheHandler implements EventHandler {

    private final static Logger log = LoggerFactory.getLogger(ImageCacheHandler.class.getName());

    private final static HttpClient httpClient = HttpClientBuilder.create().setProxy(Main.proxy).build();

    private final static Set<ImageCacheObject> cacheQueue = Collections.synchronizedSet(new HashSet<>());

    @SuppressWarnings("unused")
    public void getImageToCache(ImageCacheObject event) {
        if(cacheQueue.contains(event)) {
            log.debug("图片 {} 已存在相同缓存任务, 跳过.", event.getStoreFile().getName());
            return;
        } else {
            cacheQueue.add(event);
        }

        try {
            log.info("图片 {} Event正在进行...({})", event.getStoreFile().getName(), Integer.toHexString(event.hashCode()));
            File storeFile = event.getStoreFile();
            log.debug("正在缓存图片 {} (Path: {})", storeFile.getName(), storeFile.getAbsolutePath());
            try {
                if(!storeFile.exists() && !storeFile.createNewFile()) {
                    log.error("无法创建文件(Path: {})", storeFile.getAbsolutePath());
                    return;
                }
            } catch (IOException e) {
                log.error("无法创建文件(Path: {})", storeFile.getAbsolutePath());
                e.printStackTrace();
            }

            HttpGet request = new HttpGet(event.getDownloadLink());
            request.addHeader("Referer", PixivURL.getPixivRefererLink(event.getIllustId()));
            HttpResponse response;
            try {
                response = httpClient.execute(request);
            } catch (IOException e) {
                log.error("Http请求时发生异常", e);
                return;
            }
            if(response.getStatusLine().getStatusCode() != 200) {
                log.warn("Http请求异常：{}", response.getStatusLine());
                return;
            }

            log.debug("正在下载...(Content-Length: {}KB)", response.getEntity().getContentLength() / 1024);
            try(FileOutputStream fos = new FileOutputStream(storeFile)) {
                IOUtils.copy(response.getEntity().getContent(), fos);
            } catch (IOException e) {
                log.error("下载图片时发生异常", e);
                return;
            }
            event.getImageCache().put(URLs.getResourceName(event.getDownloadLink()), storeFile);
        } finally {
            log.info("图片 {} Event结束({})", event.getStoreFile().getName(), Integer.toHexString(event.hashCode()));
            cacheQueue.remove(event);
        }
    }

}
