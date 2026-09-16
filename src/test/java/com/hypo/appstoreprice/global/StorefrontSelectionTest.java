package com.hypo.appstoreprice.global;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StorefrontSelectionTest {
    @Test void mainstreamIsDefaultAndFullInventoryRequiresExplicitSelection() throws Exception {
        var registry=new StorefrontRegistry();
        assertEquals(StorefrontRegistry.PRIORITY, registry.selected(List.of(),"mainstream",List.of()).stream().map(Models.Storefront::code).toList());
        assertEquals(175,registry.selected(List.of(),"all",List.of()).size());
        assertEquals(List.of("cn","us"),registry.selected(List.of("US","cn","us"),"custom",List.of("hk","cn")).stream().map(Models.Storefront::code).toList());
        assertThrows(IllegalArgumentException.class,()->registry.selected(List.of(),"custom",List.of()));
        assertThrows(IllegalArgumentException.class,()->registry.selected(List.of(),"unknown",List.of()));
    }
}
