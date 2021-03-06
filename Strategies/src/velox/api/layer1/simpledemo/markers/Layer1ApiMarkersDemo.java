package velox.api.layer1.simpledemo.markers;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import velox.api.layer1.Layer1ApiAdminAdapter;
import velox.api.layer1.Layer1ApiFinishable;
import velox.api.layer1.Layer1ApiInstrumentListener;
import velox.api.layer1.Layer1ApiProvider;
import velox.api.layer1.Layer1CustomPanelsGetter;
import velox.api.layer1.annotations.Layer1Attachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.common.ListenableHelper;
import velox.api.layer1.common.Log;
import velox.api.layer1.data.ExecutionInfo;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.OrderInfoUpdate;
import velox.api.layer1.data.OrderStatus;
import velox.api.layer1.data.OrderType;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.datastructure.events.OrderExecutedEvent;
import velox.api.layer1.datastructure.events.OrderUpdatedEvent;
import velox.api.layer1.datastructure.events.OrderUpdatesExecutionsAggregationEvent;
import velox.api.layer1.datastructure.events.TradeAggregationEvent;
import velox.api.layer1.layers.strategies.interfaces.CalculatedResultListener;
import velox.api.layer1.layers.strategies.interfaces.InvalidateInterface;
import velox.api.layer1.layers.strategies.interfaces.Layer1IndicatorColorInterfaceReceiver;
import velox.api.layer1.layers.strategies.interfaces.OnlineCalculatable;
import velox.api.layer1.layers.strategies.interfaces.OnlineValueCalculatorAdapter;
import velox.api.layer1.messages.UserMessageLayersChainCreatedTargeted;
import velox.api.layer1.messages.indicators.DataStructureInterface;
import velox.api.layer1.messages.indicators.DataStructureInterface.StandardEvents;
import velox.api.layer1.messages.indicators.DataStructureInterface.TreeResponseInterval;
import velox.api.layer1.messages.indicators.IndicatorColorInterface;
import velox.api.layer1.messages.indicators.IndicatorColorScheme;
import velox.api.layer1.messages.indicators.IndicatorLineStyle;
import velox.api.layer1.messages.indicators.Layer1ApiDataInterfaceRequestMessage;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.colors.ColorsChangedListener;
import velox.gui.StrategyPanel;
import velox.gui.colors.Colors;
import velox.gui.colors.ColorsConfigItem;

@Layer1Attachable
@Layer1StrategyName("Markers demo")
public class Layer1ApiMarkersDemo implements
    Layer1ApiFinishable,
    Layer1ApiAdminAdapter,
    Layer1ApiInstrumentListener, OnlineCalculatable,
    Layer1CustomPanelsGetter, Layer1IndicatorColorInterfaceReceiver {
 
    private static final String INDICATOR_NAME_TRADE = "Trade markers";
    private static final String INDICATOR_NAME_CIRCLES = "Order markers";
    private static final String INDICATOR_COLOR_NAME = "Trade markers line";
    private static final String INDICATOR_CIRCLES_COLOR_NAME = "Markers order circles";
    private static final Color INDICATOR_CIRCLES_DEFAULT_COLOR = Color.GREEN;
    
    private Layer1ApiProvider provider;
    
    private IndicatorColorInterface indicatorColorInterface;
    
    private Map<String, String> indicatorsFullNameToUserName = new HashMap<>();
    private Map<String, String> indicatorsUserNameToFullName = new HashMap<>();
    
    private Map<String, InvalidateInterface> invalidateInterfaceMap = new ConcurrentHashMap<>();

    private Map<String, Double> pipsMap = new ConcurrentHashMap<>();
    
    private DataStructureInterface dataStructureInterface;
    
    private BufferedImage tradeIcon = new BufferedImage(16, 16, BufferedImage.TYPE_4BYTE_ABGR);
    private BufferedImage orderIcon = new BufferedImage(16, 16, BufferedImage.TYPE_4BYTE_ABGR);

    public Layer1ApiMarkersDemo(Layer1ApiProvider provider) {
        this.provider = provider;
        
        ListenableHelper.addListeners(provider, this);
        
        // Prepare trade marker
        Graphics graphics = tradeIcon.getGraphics();
        graphics.setColor(Color.BLUE);
        graphics.drawLine(0, 0, 15, 15);
        graphics.drawLine(15, 0, 0, 15);
        
        reloadOrderIcon();
    }
    
    private void reloadOrderIcon() {
        if (indicatorColorInterface != null) {
            Graphics graphics = orderIcon.getGraphics();
            graphics.setColor(Colors.TRANSPARENT);
            graphics.fillRect(0, 0, 15, 15);
            graphics.setColor(indicatorColorInterface.getOrDefault(INDICATOR_CIRCLES_COLOR_NAME, INDICATOR_CIRCLES_DEFAULT_COLOR));
            graphics.drawOval(0, 0, 15, 15);
        }
    }
    
    @Override
    public void finish() {
        synchronized (indicatorsFullNameToUserName) {
            for (String userName: indicatorsFullNameToUserName.values()) {
                provider.sendUserMessage(new Layer1ApiUserMessageModifyIndicator(Layer1ApiMarkersDemo.class, userName, false));
            }
        }
        invalidateInterfaceMap.clear();
    }
    
    private Layer1ApiUserMessageModifyIndicator getUserMessageAdd(String userName,
            IndicatorLineStyle lineStyle, boolean isAddWidget) {
        return new Layer1ApiUserMessageModifyIndicator(Layer1ApiMarkersDemo.class, userName, true,
                new IndicatorColorScheme() {
                    @Override
                    public ColorDescription[] getColors() {
                        return new ColorDescription[] {
                                new ColorDescription(Layer1ApiMarkersDemo.class, INDICATOR_COLOR_NAME, Color.red, true),
                                new ColorDescription(Layer1ApiMarkersDemo.class, INDICATOR_CIRCLES_COLOR_NAME, INDICATOR_CIRCLES_DEFAULT_COLOR, false)
                        };
                    }
                    
                    @Override
                    public String getColorFor(Double value) {
                        return INDICATOR_COLOR_NAME;
                    }

                    @Override
                    public ColorIntervalResponse getColorIntervalsList(double valueFrom, double valueTo) {
                        return new ColorIntervalResponse(new String[] {INDICATOR_COLOR_NAME}, new double[] {});
                    }
                }, lineStyle, Color.white, Color.black, null,
                null, null, null, null, GraphType.PRIMARY, isAddWidget, false, null, this, null);
    }
    
    @Override
    public void onUserMessage(Object data) {
        if (data.getClass() == UserMessageLayersChainCreatedTargeted.class) {
            UserMessageLayersChainCreatedTargeted message = (UserMessageLayersChainCreatedTargeted) data;
            if (message.targetClass == getClass()) {
                provider.sendUserMessage(new Layer1ApiDataInterfaceRequestMessage(dataStructureInterface -> this.dataStructureInterface = dataStructureInterface));
                addIndicator(INDICATOR_NAME_TRADE);
                addIndicator(INDICATOR_NAME_CIRCLES);
            }
        }
    }
    
    @Override
    public void onInstrumentAdded(String alias, InstrumentInfo instrumentInfo) {
        pipsMap.put(alias, instrumentInfo.pips);
    }

    @Override
    public void onInstrumentRemoved(String alias) {
        pipsMap.remove(alias);
    }
    
    @Override
    public void onInstrumentNotFound(String symbol, String exchange, String type) {
    }

    @Override
    public void onInstrumentAlreadySubscribed(String symbol, String exchange, String type) {
    }
    
    @Override
    public void calculateValuesInRange(String indicatorName, String alias, long t0, long intervalWidth, int intervalsNumber,
            CalculatedResultListener listener) {
     
        String userName = indicatorsFullNameToUserName.get(indicatorName);
        
        switch (userName) {
        case INDICATOR_NAME_TRADE: {
            ArrayList<TreeResponseInterval> intervalResponse = dataStructureInterface.get(t0, intervalWidth, intervalsNumber, alias,
                    new StandardEvents[] {StandardEvents.TRADE});
            
            double lastPrice = ((TradeAggregationEvent) intervalResponse.get(0).events.get(StandardEvents.TRADE.toString())).lastPrice;
            
            for (int i = 1; i <= intervalsNumber; ++i) {
                TradeAggregationEvent trades = (TradeAggregationEvent)intervalResponse.get(i).events.get(StandardEvents.TRADE.toString());
                
                if (!Double.isNaN(trades.lastPrice)) {
                    lastPrice = trades.lastPrice;
                }
                
                if (trades.askAggressorMap.isEmpty() && trades.bidAggressorMap.isEmpty()) {
                    listener.provideResponse(lastPrice);
                } else {
                    listener.provideResponse(new Marker(lastPrice, -tradeIcon.getHeight() / 2, -tradeIcon.getWidth() / 2, tradeIcon));
                }
            }
            
            listener.setCompleted();
            break;
        } case INDICATOR_NAME_CIRCLES: {
            ArrayList<TreeResponseInterval> intervalResponse = dataStructureInterface.get(t0, intervalWidth, intervalsNumber, alias,
                    new StandardEvents[] {StandardEvents.ORDER});
            for (int i = 1; i <= intervalsNumber; ++i) {
                OrderUpdatesExecutionsAggregationEvent orders = (OrderUpdatesExecutionsAggregationEvent) intervalResponse.get(i).events.get(StandardEvents.ORDER.toString());
                
                ArrayList<Marker> result = new ArrayList<>();
                
                for (Object object: orders.orderUpdates) {
                    if (object instanceof OrderExecutedEvent) {
                        OrderExecutedEvent orderExecutedEvent = (OrderExecutedEvent) object;
                        result.add(new Marker(orderExecutedEvent.executionInfo.price / pipsMap.getOrDefault(orderExecutedEvent.alias, 1.),
                                -orderIcon.getHeight() / 2, -orderIcon.getWidth() / 2, orderIcon));
                    } else if (object instanceof OrderUpdatedEvent) {
                        OrderUpdatedEvent orderUpdatedEvent = (OrderUpdatedEvent) object;
                        if (orderUpdatedEvent.orderInfoUpdate.status == OrderStatus.CANCELLED) {
                            result.add(new Marker(getActivePrice(orderUpdatedEvent.orderInfoUpdate) / pipsMap.getOrDefault(orderUpdatedEvent.alias, 1.),
                                    -orderIcon.getHeight() / 2, -orderIcon.getWidth() / 2, orderIcon));
                        }
                    }
                }
                
                listener.provideResponse(result);
            }
            
            listener.setCompleted();
            break;
        } default:
            throw new IllegalArgumentException("Unknown indicator name " + indicatorName);
        }
        
    }
    
    private double getActivePrice(OrderInfoUpdate orderInfoUpdate) {
        return (orderInfoUpdate.type == OrderType.STP || orderInfoUpdate.type == OrderType.STP_LMT)
                ? orderInfoUpdate.stopPrice : orderInfoUpdate.limitPrice;
    }
    
    @Override
    public OnlineValueCalculatorAdapter createOnlineValueCalculator(String indicatorName, String indicatorAlias, long time,
            Consumer<Object> listener, InvalidateInterface invalidateInterface) {
        String userName = indicatorsFullNameToUserName.get(indicatorName);
        
        invalidateInterfaceMap.put(userName, invalidateInterface);
        
        switch (userName) {
        case INDICATOR_NAME_TRADE:
            return new OnlineValueCalculatorAdapter() {
                @Override
                public void onTrade(String alias, double price, int size, TradeInfo tradeInfo) {
                    if (alias.equals(indicatorAlias)) {
                        listener.accept(new Marker(price, -tradeIcon.getHeight() / 2, -tradeIcon.getWidth() / 2, tradeIcon));
                    }
                }
            };
        case INDICATOR_NAME_CIRCLES:
            return new OnlineValueCalculatorAdapter() {
                private Map<String, String> orderIdToAlias = new HashMap<>();
                
                @Override
                public void onOrderExecuted(ExecutionInfo executionInfo) {
                    String alias = orderIdToAlias.get(executionInfo.orderId);
                    if (alias != null) {
                        if (alias.equals(indicatorAlias)) {
                            Double pips = pipsMap.get(alias);
                            if (pips != null) {
                                listener.accept(new Marker(executionInfo.price / pips, -orderIcon.getHeight() / 2, -orderIcon.getWidth() / 2, orderIcon));
                            } else {
                                Log.info("Unknown pips for instrument " + alias);
                            }
                            
                        }
                    } else {
                        Log.warn("Markers demo: Unknown alias for execution with order id " + executionInfo.orderId);
                    }
                }
                
                @Override
                public void onOrderUpdated(OrderInfoUpdate orderInfoUpdate) {
                    if (orderInfoUpdate.instrumentAlias.equals(indicatorAlias)) {
                        if (orderInfoUpdate.status == OrderStatus.CANCELLED) {
                            Double pips = pipsMap.get(orderInfoUpdate.instrumentAlias);
                            if (pips != null) {
                                listener.accept(new Marker(getActivePrice(orderInfoUpdate) / pips, -orderIcon.getHeight() / 2, -orderIcon.getWidth() / 2, orderIcon));
                            } else {
                                Log.info("Unknown pips for instrument " + orderInfoUpdate.instrumentAlias);
                            }
                        }
                    }
                    orderIdToAlias.put(orderInfoUpdate.orderId, orderInfoUpdate.instrumentAlias);
                }
            };
        default:
            throw new IllegalArgumentException("Unknown indicator name " + indicatorName);
        }
    }
    
    @Override
    public void acceptIndicatorColorInterface(IndicatorColorInterface indicatorColorInterface) {
        this.indicatorColorInterface = indicatorColorInterface;
        reloadOrderIcon();
    }
    
    @Override
    public StrategyPanel[] getCustomGuiFor(String alias, String indicatorName) {
        StrategyPanel panel = new StrategyPanel("Markers demo", new GridBagLayout());
        
        panel.setLayout(new GridBagLayout());
        GridBagConstraints gbConst;
        
        ColorsConfigItem configItemPositive = new ColorsConfigItem(INDICATOR_CIRCLES_COLOR_NAME, INDICATOR_CIRCLES_COLOR_NAME, true,
            INDICATOR_CIRCLES_DEFAULT_COLOR, indicatorColorInterface, new ColorsChangedListener() {
            @Override
            public void onColorsChanged() {
                reloadOrderIcon();
                
                InvalidateInterface invalidaInterface = invalidateInterfaceMap.get(INDICATOR_NAME_CIRCLES);
                if (invalidaInterface != null) {
                    invalidaInterface.invalidate();
                }
            }
        });
        
        gbConst = new GridBagConstraints();
        gbConst.gridx = 0;
        gbConst.gridy = 0;
        gbConst.weightx = 1;
        gbConst.insets = new Insets(5, 5, 5, 5);
        gbConst.fill = GridBagConstraints.HORIZONTAL;
        panel.add(configItemPositive, gbConst);
        
        return new StrategyPanel[] {panel};
    }

    public void addIndicator(String userName) {
        Layer1ApiUserMessageModifyIndicator message = null;
        switch (userName) {
        case INDICATOR_NAME_TRADE:
            message = getUserMessageAdd(userName, IndicatorLineStyle.SHORT_DASHES_WIDE_LEFT_NARROW_RIGHT, true);
            break;
        case INDICATOR_NAME_CIRCLES:
            message = getUserMessageAdd(userName, IndicatorLineStyle.NONE, true);
            break;
        default:
            Log.warn("Unknwon name for marker indicator: " + userName);
            break;
        }
        
        if (message != null) {
            synchronized (indicatorsFullNameToUserName) {
                indicatorsFullNameToUserName.put(message.fullName, message.userName);
                indicatorsUserNameToFullName.put(message.userName, message.fullName);
            }
            provider.sendUserMessage(message);
        }
    }
}
