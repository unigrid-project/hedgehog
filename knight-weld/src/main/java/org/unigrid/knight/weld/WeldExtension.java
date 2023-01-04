package org.unigrid.knight.weld;



import java.lang.reflect.Array;
import java.util.List;
import org.apache.geronimo.arthur.spi.ArthurExtension;
import org.apache.geronimo.arthur.spi.model.DynamicProxyModel;
import org.apache.geronimo.arthur.spi.model.ResourceModel;

public class WeldExtension implements ArthurExtension {
	@Override
	public void execute(Context context) {
		System.out.println("shit");
		/*DynamicProxyModel model = new DynamicProxyModel(List.of("jakarta.enterprise.event.Event"));
		context.register(model);*/
	        context.register(new ResourceModel("META-INF/native-image/weld-proxies.json"));
	}

}
