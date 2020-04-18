package net.lamgc.cgj.bot.event;

import com.google.common.base.Strings;
import net.lamgc.cgj.bot.BotCode;
import net.lamgc.cgj.bot.cache.CacheStore;
import net.lamgc.cgj.bot.cache.HotDataCacheStore;
import net.lamgc.cgj.bot.cache.LocalHashCacheStore;
import net.lamgc.cgj.bot.cache.StringRedisCacheStore;
import net.mamoe.mirai.message.ContactMessage;
import net.mamoe.mirai.message.FriendMessage;
import net.mamoe.mirai.message.GroupMessage;
import net.mamoe.mirai.message.data.CombinedMessage;
import net.mamoe.mirai.message.data.Image;
import net.mamoe.mirai.message.data.Message;
import net.mamoe.mirai.message.data.MessageUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MiraiMessageEvent extends MessageEvent {

    private final ContactMessage messageObject;
    private final static Logger log = LoggerFactory.getLogger(MiraiMessageEvent.class.getSimpleName());
    private final static CacheStore<String> imageIdCache = new HotDataCacheStore<>(
            new StringRedisCacheStore(BotEventHandler.redisServer, "mirai.imageId"),
            new LocalHashCacheStore<>(),
            5400000, 1800000);


    public MiraiMessageEvent(ContactMessage message) {
        super(message instanceof GroupMessage ? ((GroupMessage) message).getGroup().getId() : 0,
                message.getSender().getId(), message.getMessage().toString());
        this.messageObject = Objects.requireNonNull(message);
    }

    @Override
    public int sendMessage(final String message) {
        log.debug("处理前的消息内容:\n{}", message);
        Message msgBody = processMessage(Objects.requireNonNull(message));
        log.debug("处理后的消息内容(可能出现乱序的情况, 但实际上顺序是没问题的):\n{}", msgBody);
        if(getFromGroup() == 0) {
            FriendMessage msgObject = (FriendMessage) messageObject;
            //FIXME(LamGC, 2020.04.10): 当前 Mirai 不支持私聊长文本, 所以发生异常是正常情况...
            msgObject.getSender().sendMessage(msgBody);
        } else {
            GroupMessage msgObject = (GroupMessage) messageObject;
            msgObject.getGroup().sendMessage(msgBody);
        }
        return 0;
    }

    @Override
    public String getImageUrl(String imageId) {
        return messageObject.getBot().queryImageUrl(MessageUtils.newImage(imageId));
    }

    private final static Pattern cqCodePattern = Pattern.compile("\\[.*?:.*?]");
    private Message processMessage(final String message) {
        Matcher matcher = cqCodePattern.matcher(message);
        ArrayList<String> cqCode = new ArrayList<>();
        while (matcher.find()) {
            cqCode.add(matcher.group());
        }
        String[] texts = message
                .replaceAll("&", "&38")
                .replaceAll("\\{", "&" + Character.getNumericValue('{'))
                .replaceAll(cqCodePattern.pattern(), "|{BotCode}|")
                .replaceAll("&" + Character.getNumericValue('{'), "{")
                .replaceAll("&38", "&")
                .split("\\|");

        CombinedMessage chain = MessageUtils.newChain().plus("");
        int codeIndex = 0;
        for(String text : texts) {
            if(text.equals("{BotCode}")) {
                BotCode code;
                try {
                    code = BotCode.parse(cqCode.get(codeIndex++));
                } catch(IllegalArgumentException e) {
                    log.warn("解析待发送消息内的BotCode时发生异常, 请检查错误格式BotCode的来源并尽快排错!", e);
                    continue;
                }
                chain = chain.plus(processBotCode(code));
            } else {
                chain = chain.plus(text);
            }
        }

        return chain;
    }

    private Message processBotCode(BotCode code) {
        switch(code.getFunctionName().toLowerCase()) {
            case "image":
                if(code.containsParameter("id")) {
                    return MessageUtils.newImage(code.getParameter("id"));
                } else if(code.containsParameter("absolutePath")) {
                    return uploadImage(code);
                } else {
                    return MessageUtils.newChain("(参数不存在)");
                }
            default:
                log.warn("解析到不支持的BotCode: {}", code);
                return MessageUtils.newChain("(不支持的BotCode)");
        }
    }

    private Image uploadImage(BotCode code) {
        return uploadImage(getMessageSource(this.messageObject), code, this::uploadImage0);
    }

    /**
     * 存在缓存的上传图片.
     * @param sourceType 消息来源
     * @param code 图片BotCode
     * @param imageUploader 图片上传器
     * @return Image对象
     */
    public static Image uploadImage(MessageSource sourceType, BotCode code, Function<File, Image> imageUploader) {
        log.debug("传入BotCode信息:\n{}", code);
        String absolutePath = code.getParameter("absolutePath");
        if(Strings.isNullOrEmpty(absolutePath)) {
            throw new IllegalArgumentException("BotCode does not contain the absolutePath parameter");
        }

        String imageName = code.getParameter("imageName");
        if(!Strings.isNullOrEmpty(imageName)) {
            Image image = null;
            imageName = (sourceType + "." + imageName).intern();
            if(!imageIdCache.exists(imageName) ||
            Strings.nullToEmpty(code.getParameter("updateCache")).equalsIgnoreCase("true")) {
                synchronized (imageName) {
                    if(!imageIdCache.exists(imageName) ||
                     Strings.nullToEmpty(code.getParameter("updateCache")) .equalsIgnoreCase("true")) {
                        log.debug("imageName [{}] 缓存失效或强制更新, 正在更新缓存...", imageName);
                        image = imageUploader.apply(new File(absolutePath));
                        if(Objects.isNull(image)) {
                            return null;
                        }

                        String cacheExpireAt;
                        long expireTime = 0;
                        if(!Strings.isNullOrEmpty(cacheExpireAt = code.getParameter("cacheExpireAt"))) {
                            try {
                                expireTime = Integer.parseInt(cacheExpireAt);
                            } catch (NumberFormatException e) {
                                log.warn("BotCode中的cacheExpireAt参数无效: {}", cacheExpireAt);
                            }
                        }
                        imageIdCache.update(imageName, image.getImageId(), expireTime);
                        log.info("imageName [{}] 缓存更新完成.(有效时间: {})", imageName, expireTime);
                    } else {
                        log.debug("ImageName: [{}] 缓存命中.", imageName);
                    }
                }
            } else {
                log.debug("ImageName: [{}] 缓存命中.", imageName);
            }

            if(image == null) {
                image = MessageUtils.newImage(imageIdCache.getCache(imageName));
            }

            log.debug("ImageName: {}, ImageId: {}", imageName, image.getImageId());
            return image;
        } else {
            log.debug("未设置imageName, 无法使用缓存.");
            return imageUploader.apply(new File(absolutePath));
        }
    }

    private Image uploadImage0(File imageFile) {
        if(messageObject instanceof FriendMessage) {
            return messageObject.getSender().uploadImage(imageFile);
        } else if(messageObject instanceof GroupMessage) {
            return ((GroupMessage) messageObject).getGroup().uploadImage(imageFile);
        } else {
            log.warn("未知的ContactMessage类型: " + messageObject.toString());
            return null;
        }
    }

    public static MessageSource getMessageSource(ContactMessage messageObject) {
        if(messageObject instanceof FriendMessage) {
            return MessageSource.Private;
        } else if(messageObject instanceof GroupMessage) {
            return MessageSource.Group;
        } else {
            log.warn("未知的ContactMessage类型: " + messageObject.toString());
            return MessageSource.Unknown;
        }
    }

    /**
     * 消息来源
     */
    public enum MessageSource {
        /**
         * 私聊消息
         */
        Private,
        /**
         * 群组消息
         */
        Group,
        /**
         * 讨论组消息
         * @deprecated 已被QQ取消
         */
        Discuss,
        /**
         * 未知来源
         */
        Unknown
    }

}
