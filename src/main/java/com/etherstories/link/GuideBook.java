package com.etherstories.link;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

public final class GuideBook {

    private GuideBook() {}

    public static boolean seen(ESLinkPlugin plugin, Player p) {
        Byte v = p.getPersistentDataContainer().get(key(plugin), PersistentDataType.BYTE);
        return v != null && v == (byte) 1;
    }

    public static void markSeen(ESLinkPlugin plugin, Player p) {
        p.getPersistentDataContainer().set(key(plugin), PersistentDataType.BYTE, (byte) 1);
    }

    public static void open(ESLinkPlugin plugin, Player p) {
        markSeen(plugin, p);
        p.closeInventory();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                p.openBook(written());
            } catch (Throwable t) {
                plugin.msg(p, "这本书打不开（核心不支持）。看 /link 大厅里的说明，或问管理。");
            }
        });
    }

    public static ItemStack icon(boolean first) {
        return Items.named(Material.WRITTEN_BOOK,
                first ? "&e&l说明书 &6(第一次点这里)" : "&e说明书",
                List.of("&7翻页看怎么用大厅、市场和运输箱", "&8或输入 /link help"));
    }

    static ItemStack written() {
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        if (!(book.getItemMeta() instanceof BookMeta meta)) return book;
        meta.setTitle("互通说明书");
        meta.setAuthor("ESLink");
        meta.setPages(List.of(
                page("互通说明书",
                        "输入 /link 打开大厅。",
                        "",
                        "顶上一排是各服务器：",
                        "显示名字和简介。",
                        "彩色方块=在线，灰=离线。",
                        "点了只看该服的货。",
                        "灰玻璃是空位，点了没反应。"),
                page("市场",
                        "看各服上架的货物。",
                        "左键购买，右键看卖家主页。",
                        "",
                        "指南针=搜索：关掉界面后",
                        "在聊天栏打物品名，回车回来。",
                        "对面没有的物品是屏障，买不了。",
                        "模组附魔对面没有会丢掉，",
                        "物品上会写缺什么；带回原服可恢复。"),
                page("上架 / 下架",
                        "主手拿货，点绿宝石，",
                        "聊天栏输入单价。",
                        "成功后货从背包拿走。",
                        "",
                        "「我的上架」左键下架。",
                        "右键改价，聊天栏输新数字。",
                        "背包满了会掉脚下并提示。",
                        "买不了自己上的货。",
                        "大厅可开「上架通知」，默认关。"),
                page("聊天",
                        "大厅可切：仅本服 / 全部互通服。",
                        "/link chat  开关",
                        "/link msg 玩家 内容  跨服私聊",
                        "",
                        "开互通时本服发言带 [互通]。",
                        "外服消息带颜色。可用 &a 等。",
                        "点名字或 [屏蔽] 可屏蔽。",
                        "点 [回] 可私聊对方。",
                        "开着全部时说太快，本条不传对面。"),
                page("物品展示 [i]",
                        "聊天里打 [i]，会显示主手里的物品。",
                        "空手是 [空手]。",
                        "跨服同样有效。"),
                page("运输箱",
                        "看准箱子 /link 或 /link chest。",
                        "蹲下左/右键牌子打开菜单。",
                        "直接左键只拆牌。拆除要点两次。",
                        "只有操作员或管理能改/拆。",
                        "TX 可过滤物品或模组；空手点清。",
                        "大厅「我的节点」可指向坐标。",
                        "大箱按一整口算，两边漏斗都算。",
                        "纸箱/潜影盒连里面的东西一起传。",
                        "大包会倒计时再发，小件照常走。",
                        "发送箱必须另绑一口回退箱。",
                        "对面没有的物品会退回回退箱。",
                        "回退箱满了发送会停。"),
                page("红石控制器",
                        "用红石灯。灯亮=在线。",
                        "离线变灰、故障变红。",
                        "接收灯本身输出 0–15。",
                        "对端掉线：灰块，输出 0。",
                        "蹲下左键牌子打开菜单。",
                        "别人的灯敲不掉。",
                        "接收可切正常/反向/满信号。"),
                page("指令",
                        "/link  大厅",
                        "/link chest  运输箱",
                        "/link io  红石",
                        "/link msg 玩家 内容",
                        "/link help  这本书",
                        "/link version  看版本",
                        "/link log  复制日志",
                        "/link chat local|all",
                        "/link ignore player 名",
                        "/link reload  管理重载",
                        "",
                        "钱走本服经济，不共用账户。")
        ));
        book.setItemMeta(meta);
        return book;
    }

    private static String page(String title, String... lines) {
        StringBuilder sb = new StringBuilder(title).append("\n\n");
        for (String line : lines) sb.append(line).append("\n");
        return sb.toString().stripTrailing();
    }

    private static NamespacedKey key(ESLinkPlugin plugin) {
        return new NamespacedKey(plugin, "guide");
    }
}
