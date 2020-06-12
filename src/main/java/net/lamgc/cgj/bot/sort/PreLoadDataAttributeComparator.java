package net.lamgc.cgj.bot.sort;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.lamgc.cgj.bot.cache.CacheStoreCentral;

import java.io.IOException;
import java.util.Comparator;

/**
 * 收藏数比较器
 */
public class PreLoadDataAttributeComparator implements Comparator<JsonElement> {

    private final PreLoadDataAttribute attribute;

    public PreLoadDataAttributeComparator(PreLoadDataAttribute attribute) {
        this.attribute = attribute;
    }

    @Override
    public int compare(JsonElement o1, JsonElement o2) {
        if(!o1.isJsonObject() || !o2.isJsonObject()) {
            if(o1.isJsonObject()) {
                return 1;
            } else if(o2.isJsonObject()) {
                return -1;
            } else {
                return 0;
            }
        }
        if(!o1.getAsJsonObject().has("illustId") || !o2.getAsJsonObject().has("illustId")) {
            if(o1.getAsJsonObject().has("illustId")) {
                return 1;
            } else if(o2.getAsJsonObject().has("illustId")) {
                return -1;
            } else {
                return 0;
            }
        }
        try {
            JsonObject illustPreLoadData1 = CacheStoreCentral.getCentral()
                    .getIllustPreLoadData(o1.getAsJsonObject().get("illustId").getAsInt(), false);
            JsonObject illustPreLoadData2 = CacheStoreCentral.getCentral()
                    .getIllustPreLoadData(o2.getAsJsonObject().get("illustId").getAsInt(), false);
            return Integer.compare(
                    illustPreLoadData2.get(attribute.attrName).getAsInt(),
                    illustPreLoadData1.get(attribute.attrName).getAsInt());
        } catch (IOException e) {
            e.printStackTrace();
            return 0;
        }
    }

}
