package net.lamgc.cgj.bot.event;

import net.lamgc.cgj.bot.BotCode;
import net.lamgc.cgj.bot.message.MessageSender;
import net.lamgc.cgj.bot.message.MessageSource;
import net.lamgc.cgj.bot.message.SpringCQMessageSender;
import net.lz1998.cq.event.message.CQDiscussMessageEvent;
import net.lz1998.cq.event.message.CQGroupMessageEvent;
import net.lz1998.cq.event.message.CQMessageEvent;
import net.lz1998.cq.robot.CoolQ;

import java.net.InetSocketAddress;
import java.util.Objects;

public class SpringCQMessageEvent extends MessageEvent {

    private final CoolQ cq;
    private final MessageSender messageSender;

    public SpringCQMessageEvent(CoolQ cq, CQMessageEvent messageEvent) {
        super(messageEvent instanceof CQGroupMessageEvent ? (
                (CQGroupMessageEvent) messageEvent).getGroupId() :
              messageEvent instanceof CQDiscussMessageEvent ?
                ((CQDiscussMessageEvent) messageEvent).getDiscussId() : 0,
              messageEvent.getUserId(), messageEvent.getMessage());
        this.cq = Objects.requireNonNull(cq);
        MessageSource source;
        if(messageEvent instanceof CQGroupMessageEvent) {
            source = MessageSource.Group;
        } else if (messageEvent instanceof CQDiscussMessageEvent) {
            source = MessageSource.Discuss;
        } else {
            source = MessageSource.Private;
        }
        messageSender = new SpringCQMessageSender(cq, source, source == MessageSource.Private ? getFromQQ() : getFromGroup());
    }

    @Override
    public int sendMessage(final String message) {
        return messageSender.sendMessage(message);
    }

    /**
     * 通过CQ码获取图片下载链接.
     * @param imageFileName 图片完整CQ码
     * @return 图片下载链接
     */
    @Override
    public String getImageUrl(String imageFileName) {
        BotCode code;
        if(imageFileName.startsWith("[CQ:") && imageFileName.endsWith("]")) {
            code = BotCode.parse(imageFileName);
            return code.getParameter("url");
        } else {
            InetSocketAddress remoteAddress = cq.getBotSession().getRemoteAddress();
            if(remoteAddress == null) {
                throw new IllegalStateException("remoteAddress failed to get");
            }
            String file = cq.getImage(imageFileName).getData().getFile().replaceAll("\\\\", "/");
            return "http://" + remoteAddress.getHostString() + ":5700/data" + file.substring(file.lastIndexOf("/data") + 5);
        }
    }

}
