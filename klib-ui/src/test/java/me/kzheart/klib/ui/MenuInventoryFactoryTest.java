package me.kzheart.klib.ui;
import java.lang.reflect.Proxy;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MenuInventoryFactoryTest {
    @Test void factoryReceivesRichTitleAndMustPreserveOwnership() {
        MenuModel model=MenuCompiler.compile(MenuTemplate.builder("<font:custom:menu>标题",3).build());
        InventoryHolder holder=()->null;
        Inventory expected=inventory(holder,27);
        assertSame(expected,MenuRenderer.createInventory((owner,size,title)->{
            assertSame(holder,owner);assertEquals(27,size);assertEquals(model.title(),title);return expected;
        },holder,model));
        assertThrows(IllegalArgumentException.class,()->MenuRenderer.createInventory((h,s,t)->inventory(()->null,s),holder,model));
        assertThrows(IllegalArgumentException.class,()->MenuRenderer.createInventory((h,s,t)->inventory(h,9),holder,model));
        assertThrows(NullPointerException.class,()->MenuRenderer.createInventory((h,s,t)->null,holder,model));
    }
    private Inventory inventory(InventoryHolder holder,int size) {
        return (Inventory)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Inventory.class},(p,m,a)->{
            if(m.getName().equals("getHolder"))return holder;if(m.getName().equals("getSize"))return Integer.valueOf(size);return null;
        });
    }
}
