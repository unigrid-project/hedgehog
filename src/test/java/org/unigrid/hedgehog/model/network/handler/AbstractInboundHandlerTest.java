package org.unigrid.hedgehog.model.network.handler;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.AttributeKey;
import java.util.Optional;
import lombok.Data;
import lombok.SneakyThrows;
import org.unigrid.hedgehog.model.network.packet.Packet;
import mockit.Mocked;
import net.jqwik.api.lifecycle.BeforeProperty;
import net.jqwik.api.lifecycle.AfterTry;
import mockit.Capturing;
import mockit.Expectations;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Property;
import net.jqwik.api.ForAll;
import net.jqwik.api.Provide;
import org.unigrid.hedgehog.jqwik.BaseMockedWeldTest;

public class AbstractInboundHandlerTest extends BaseMockedWeldTest {
	EmbeddedChannel channel = new EmbeddedChannel();
	
	@Provide
	public Arbitrary<AttributeKey<Boolean>> provideAttributeKey() {
		return Arbitraries.of(AttributeKey.valueOf("FILTERED"), AttributeKey.valueOf("WHATEVER"));
	}
	
	@Capturing private ChannelHandlerContext ctx;
	
	public static class PickleModel  extends Packet {
	}
	
	public static class ParsnipModel  extends Packet {
	}

	@Data
	public static class PickleHandler extends AbstractInboundHandler<PickleModel> {
		private int invocations; 
		public static final AttributeKey<Boolean> FILTERED_KEY = AttributeKey.valueOf("FILTERED");
		public PickleHandler() {
			super(Optional.of(FILTERED_KEY), PickleModel.class); 
		}
		
		@Override
		public void typedChannelRead(ChannelHandlerContext ctx, PickleModel obj) throws Exception {
			invocations++;
		}
	}
	
	@BeforeProperty
	public void beforeHandler(){
		new Expectations(){{
			ctx.channel(); result=channel;
		}};
	}
	
	@AfterTry
	public void afterHandler(){
		channel.attr(AttributeKey.valueOf("FILTERED")).set(null);
		channel.attr(AttributeKey.valueOf("WHATEVER")).set(null);
	}
	
	
	
	@SneakyThrows
	@Property
	public void shouldBeCalledWhenNotFilterred(@ForAll boolean filtered, 
		@ForAll("provideAttributeKey") AttributeKey<Boolean> key, 
		@Mocked PickleModel pickle){
		final PickleHandler pickleHandler = new PickleHandler();
		ctx.channel().attr(key).set(filtered);
		pickleHandler.channelRead(ctx, pickle);
		
		assertThat(pickleHandler.getInvocations(), 
			is(filtered && key.equals(AttributeKey.valueOf("FILTERED")) ? 0 : 1)
		);
	}
	
	
	@SneakyThrows
	@Property
	public void shouldNotBeCalledWhenWrongHandler(@ForAll boolean filtered, 
		@ForAll("provideAttributeKey") AttributeKey<Boolean> key, 
		@Mocked ParsnipModel parsnip){
		final PickleHandler pickleHandler = new PickleHandler();

		ctx.channel().attr(key).set(filtered);
		pickleHandler.channelRead(ctx, parsnip);
		
		
		assertThat(pickleHandler.getInvocations(), is(0));
	}
}
