package me.kzheart.klib.ui.chat;

import java.util.Collections;
import me.kzheart.klib.lang.MessageColor;
import me.kzheart.klib.lang.RichText;
import me.kzheart.klib.lang.RichTextSegment;

/** 面板内置文案；通过 {@link ChatPanelOptions.Builder#text(ChatPanelText, RichText)} 替换。 */
public enum ChatPanelText {
    /** 会话已关闭或空闲过期后点击旧按钮，发送到聊天栏。 */
    EXPIRED(MessageColor.YELLOW, "该面板已关闭或过期，请重新打开。"),
    /** 按钮所在页面已不在会话中，显示在状态行。 */
    STALE_PAGE(MessageColor.YELLOW, "这是旧面板上的按钮，已刷新为当前面板。"),
    /** 按钮在实时页面中已不存在，显示在状态行。 */
    STALE_BUTTON(MessageColor.YELLOW, "该按钮对应的内容已变化，已刷新面板。"),
    /** 页面权限或 guard 不再满足，发送到聊天栏并关闭该页。 */
    UNAVAILABLE(MessageColor.RED, "面板目标已失效或无权访问，请重新打开。"),
    NO_PERMISSION(MessageColor.RED, "你没有使用该按钮的权限。"),
    FAILED(MessageColor.RED, "操作失败，详情见服务器日志。"),
    INPUT_CANCELLED(MessageColor.GRAY, "已取消输入。"),
    INPUT_TIMEOUT(MessageColor.YELLOW, "输入超时，已取消。"),
    INPUT_INVALID(MessageColor.RED, "格式不正确，请重新输入（输入 cancel 取消）"),
    INPUT_SIGN_INVALID(MessageColor.RED, "告示牌内容格式不正确，未修改。"),
    INPUT_SIGN_TOO_LONG(MessageColor.GRAY, "当前值过长，已改用聊天输入。"),
    INPUT_CHAT_HINT(MessageColor.GRAY, "在聊天框输入，输入 cancel 取消"),
    INPUT_SIGN_HINT(MessageColor.GRAY, "请在告示牌中填写"),
    /** 告示牌第四行的提示，按纯文本发送。 */
    SIGN_LINE(MessageColor.GRAY, "↑ 在上方三行输入 ↑"),
    CLOSED(MessageColor.DARK_GRAY, "面板已关闭。"),
    PREVIOUS(MessageColor.GRAY, "‹"),
    NEXT(MessageColor.GRAY, "›"),
    BACK(MessageColor.GRAY, "返回"),
    CLOSE(MessageColor.GRAY, "关闭"),
    RESET(MessageColor.RED, "重置为默认值"),
    PREFILL(MessageColor.GOLD, "预填当前值"),
    PREFILL_HOVER(MessageColor.GRAY, "点击把当前值填入聊天框，编辑后发送"),
    CANCEL_INPUT(MessageColor.GRAY, "取消输入"),
    MODE_CHAT(MessageColor.GRAY, "输入：聊天"),
    MODE_SIGN(MessageColor.GRAY, "输入：告示牌"),
    MODE_HOVER(MessageColor.GRAY, "点击切换文本输入方式");

    private final RichText value;

    ChatPanelText(MessageColor color, String text) {
        value = new RichText(Collections.singletonList(new RichTextSegment(text, color, false, null, null)));
    }

    public RichText defaultValue() {
        return value;
    }
}
