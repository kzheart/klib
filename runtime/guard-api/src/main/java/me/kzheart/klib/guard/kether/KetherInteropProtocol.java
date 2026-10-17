package me.kzheart.klib.guard.kether;

/** TabooLib Kether OpenContainer 通道名的稳定公共常量。 */
public final class KetherInteropProtocol {

    public static final int VERSION = 1;
    public static final String PROVIDER_NAME = "KlibGuard";
    public static final String REMOTE_RESOLVE = "kether_remote_resolve";
    public static final String CREATE_FRAME = "kether_create_frame";
    public static final String CREATE_EXIT_STATUS = "kether_create_exit_status";
    public static final String CREATE_PARSED_ACTION = "kether_create_parsed_action";
    public static final String ADD_ACTION = "kether_add_action";
    public static final String REMOVE_ACTION = "kether_remove_action";

    private KetherInteropProtocol() {
    }
}
