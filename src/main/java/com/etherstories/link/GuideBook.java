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
                plugin.msg(p, "这本书无法打开。请查看大厅中的说明，或询问管理。");
            }
        });
    }

    public static ItemStack icon(boolean first) {
        return Items.named(Material.WRITTEN_BOOK,
                first ? "&e&l说明书 &6（建议先看）" : "&e说明书",
                List.of("&7大厅、市场、聊天与运输箱", "&8或输入 /link help"));
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
                        "顶上一排是市场或服务器。",
                        "彩色方块表示在线，灰色为离线。",
                        "点击市场即可切换货单。",
                        "灰玻璃是空位，点击无效。"),
                page("市场",
                        "货单来自当前选中的市场。",
                        "可在大厅或市场页点击切换。",
                        "左键购买，右键查看卖家。",
                        "",
                        "指南针用于搜索：关闭界面后",
                        "在聊天栏输入物品名，回车返回。",
                        "本服无法识别的物品无法购买。",
                        "对端缺失的模组附魔会在物品上注明；",
                        "带回原服后可以恢复。"),
                page("上架 / 下架 / 取回",
                        "主手持有物品，点击绿宝石，",
                        "在聊天栏输入单价。",
                        "成功后物品从背包移除。",
                        "",
                        "「我的上架」左键下架，右键改价。",
                        "本服自己的货请下架，不要购买。",
                        "外服自己的货可以取回：",
                        "货款不会打入你的账户，",
                        "只收取回费用（有冷却与每日上限）。",
                        "大厅可开启「上架通知」，默认关闭。"),
                page("聊天",
                        "大厅打开聊天选台：",
                        "发言：仅本服 / 全部互通服。",
                        "接收：不接收外服 / 全部 / 勾选服务器。",
                        "默认不接收外服消息。",
                        "/link chat  打开选台",
                        "/link msg 玩家 内容  跨服私聊",
                        "",
                        "发往外服时，本服发言带互通前缀。",
                        "外服消息使用该服主题色。",
                        "发言过快时，本条不会传到其他服务器。"),
                page("物品展示 [i]",
                        "聊天中输入 [i]，会显示主手物品。",
                        "空手显示为 [空手]。",
                        "跨服同样有效。"),
                page("运输箱",
                        "看准箱子 /link 或 /link chest。",
                        "或 /link stick 拿调试棒点箱子。",
                        "要对准箱子正面，大约 5 格内。",
                        "蹲下点牌子打开菜单。",
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
                        "对准红石灯 /link io。大约 5 格。",
                        "灯亮表示在线。请对准灯本身。",
                        "离线变灰、故障变红。",
                        "接收灯本身输出 0–15。",
                        "对端掉线：灰色，输出 0。",
                        "蹲下左键牌子打开菜单。",
                        "别人的灯无法拆除。",
                        "接收可切换正常 / 反向 / 满信号。"),
                page("指令",
                        "/link  大厅",
                        "/link chest  运输箱",
                        "/link io  红石",
                        "/link stick  调试棒",
                        "/link msg 玩家 内容",
                        "/link help  这本书",
                        "/link version  查看版本",
                        "/link chat  聊天选台",
                        "/link ignore player 名",
                        "/link market  管理市场（管理）",
                        "/link diag  诊断",
                        "/link transport on|off",
                        "/link reload  管理重载",
                        "",
                        "货款走买家所在服的经济，",
                        "各服账户并不共用。")
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
