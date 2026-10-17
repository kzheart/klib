package me.kzheart.klib.ui;

/** 与已编译菜单条目关联的回调。 */
@FunctionalInterface
public interface MenuAction {
    void accept(MenuClick click);

    static MenuAction none() {
        return click -> { };
    }
}
