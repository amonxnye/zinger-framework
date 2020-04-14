package com.food.ordering.zinger.dao;

import com.food.ordering.zinger.constant.Column.OrderColumn;
import com.food.ordering.zinger.constant.Column.OrderItemColumn;
import com.food.ordering.zinger.constant.Enums.OrderStatus;
import com.food.ordering.zinger.constant.Enums.Priority;
import com.food.ordering.zinger.constant.Enums.UserRole;
import com.food.ordering.zinger.constant.ErrorLog;
import com.food.ordering.zinger.constant.PaymentResponse;
import com.food.ordering.zinger.constant.Query.OrderItemQuery;
import com.food.ordering.zinger.constant.Query.OrderQuery;
import com.food.ordering.zinger.constant.Query.TransactionQuery;
import com.food.ordering.zinger.model.*;
import com.food.ordering.zinger.model.logger.OrderLogModel;
import com.food.ordering.zinger.rowMapperLambda.OrderRowMapperLambda;
import com.food.ordering.zinger.rowMapperLambda.TransactionRowMapperLambda;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.food.ordering.zinger.constant.Column.OrderColumn.*;
import static com.food.ordering.zinger.constant.Column.TransactionColumn.*;

@Repository
public class OrderDao {

    @Autowired
    NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Autowired
    UtilsDao utilsDao;

    @Autowired
    TransactionDao transactionDao;

    @Autowired
    ItemDao itemDao;

    @Autowired
    ShopDao shopDao;

    @Autowired
    RatingDao ratingDao;

    @Autowired
    UserDao userDao;

    @Autowired
    AuditLogDao auditLogDao;

    @Autowired
    ConfigurationDao configurationDao;

    @Autowired
    PaymentResponse paymentResponse;

    public Response<String> insertOrder(OrderItemListModel orderItemListModel, RequestHeaderModel requestHeaderModel) {
        /*
         *   1. Verify the amount and availability of the items using verify order
         *   2. Generate the transaction token from payment gateway
         *   3. Insert the order
         * */

        Response<String> response = new Response<>();
        Priority priority = Priority.HIGH;

        try {
            if (!utilsDao.validateUser(requestHeaderModel).getCode().equals(ErrorLog.CodeSuccess)) {
                response.setCode(ErrorLog.IH1053);
                response.setMessage(ErrorLog.InvalidHeader);
            } else {
                Response<String> verifyOrderResponse = verifyOrderDetails(orderItemListModel);

                if (verifyOrderResponse.getCode().equals(ErrorLog.CodeSuccess)) {

                    Response<String> initiateTransactionResponse = initiateTransaction(orderItemListModel.getTransactionModel().getOrderModel(), verifyOrderResponse.getData());

                    if (initiateTransactionResponse.getCode().equals(ErrorLog.CodeSuccess)) {

                        OrderModel order = orderItemListModel.getTransactionModel().getOrderModel();

                        MapSqlParameterSource parameter = new MapSqlParameterSource()
                                .addValue(OrderColumn.id, order.getId())
                                .addValue(OrderColumn.mobile, order.getUserModel().getMobile())
                                .addValue(shopId, order.getShopModel().getId())
                                .addValue(price, order.getPrice())
                                .addValue(deliveryPrice, order.getDeliveryPrice())
                                .addValue(deliveryLocation, order.getDeliveryLocation())
                                .addValue(cookingInfo, order.getCookingInfo());

                        int orderResult = namedParameterJdbcTemplate.update(OrderQuery.insertOrder, parameter);
                        if (orderResult <= 0) {
                            response.setCode(ErrorLog.ODNU1263);
                            response.setMessage(ErrorLog.OrderDetailNotUpdated);
                            response.setData(ErrorLog.TransactionTokenNotAvailable);
                        } else {
                            int i;
                            for (i = 0; i < orderItemListModel.getOrderItemsList().size(); i++) {
                                OrderItemModel orderItem = orderItemListModel.getOrderItemsList().get(i);
                                Response<String> orderItemResult = insertOrderItem(orderItem, orderItemListModel.getTransactionModel().getOrderModel().getId());
                                if (!orderItemResult.getCode().equals(ErrorLog.CodeSuccess)) {
                                    response.setCode(ErrorLog.OIDNU1262);
                                    response.setMessage(ErrorLog.OrderItemDetailNotUpdated + " : " + orderItem);
                                    response.setData(ErrorLog.TransactionTokenNotAvailable);
                                    break;
                                }
                            }
                            if (i == orderItemListModel.getOrderItemsList().size()) {
                                priority = Priority.LOW;
                                response.setCode(ErrorLog.CodeSuccess);
                                response.setMessage(ErrorLog.Success);
                                response.setData(initiateTransactionResponse.getData());
                            }
                        }
                    } else {
                        response.setCode(ErrorLog.TIF1300);
                        response.setMessage(ErrorLog.TransactionInitiationFailed);
                        response.setData(ErrorLog.TransactionTokenNotAvailable);
                    }

                } else {
                    response.setCode(verifyOrderResponse.getCode());
                    response.setMessage(verifyOrderResponse.getMessage());
                    response.setData(ErrorLog.TransactionTokenNotAvailable);
                }
            }

        } catch (Exception e) {
            response.setCode(ErrorLog.CE1261);
            response.setData(ErrorLog.TransactionTokenNotAvailable);
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        }

        auditLogDao.insertOrderLog(new OrderLogModel(response, requestHeaderModel.getMobile(), orderItemListModel.getTransactionModel().getOrderModel().getId(), orderItemListModel.toString(), priority));
        return response;
    }

    /**************************************************/

    public Response<TransactionModel> verifyOrder(String orderId) {
        Response<TransactionModel> response = new Response<>();
        TransactionModel transactionModel = null;

        try {
            Response<TransactionModel> transactionModelResponse = getTransactionStatus(orderId);
            Response<OrderModel> orderModelResponse = getOrderDetailById(orderId);

            if (transactionModelResponse.getCode().equals(ErrorLog.CodeSuccess) &&
                    transactionModelResponse.getMessage().equals(ErrorLog.Success) &&
                    orderModelResponse.getCode().equals(ErrorLog.CodeSuccess) &&
                    orderModelResponse.getMessage().equals(ErrorLog.Success)
                    //TODO: Uncomment After PAYMENT GATEWAY INTEGRATION
                    && orderModelResponse.getData().getPrice().equals(transactionModelResponse.getData().getTransactionAmount())
            ) {
                transactionModel = transactionModelResponse.getData();
                orderModelResponse.getData().setOrderStatus(paymentResponse.getOrderStatus(transactionModel));
                transactionModel.setOrderModel(orderModelResponse.getData());

                response.setCode(ErrorLog.CodeSuccess);
                response.setMessage(ErrorLog.Success);
                response.setData(transactionModel);
            }
        } catch (Exception e) {
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        }

        return response;
    }

    public Response<String> acceptOrder(String orderId, RequestHeaderModel requestHeaderModel) {
        /*
         *   1. verify the transaction status api and
         *   2. Insert the transaction in the transaction table
         * */
        Response<String> response = new Response<>();
        Priority priority = Priority.MEDIUM;

        try {
            if (!utilsDao.validateUser(requestHeaderModel).getCode().equals(ErrorLog.CodeSuccess)) {
                response.setCode(ErrorLog.IH1003);
                response.setMessage(ErrorLog.InvalidHeader);
                priority = Priority.HIGH;
            } else {
                Response<TransactionModel> verifyOrderResponse = verifyOrder(orderId);

                if (verifyOrderResponse.getCode().equals(ErrorLog.CodeSuccess) && verifyOrderResponse.getMessage().equals(ErrorLog.Success)) {
                    Response<String> insertTransactionResponse = transactionDao.insertTransactionDetails(verifyOrderResponse.getData());

                    if (insertTransactionResponse.getCode().equals(ErrorLog.CodeSuccess) && insertTransactionResponse.getMessage().equals(ErrorLog.Success)) {
                        Response<String> updateOrderStatusResponse = updateOrderStatus(verifyOrderResponse.getData().getOrderModel(), requestHeaderModel);

                        if (updateOrderStatusResponse.getCode().equals(ErrorLog.CodeSuccess) && updateOrderStatusResponse.getMessage().equals(ErrorLog.Success)) {
                            response.setCode(ErrorLog.CodeSuccess);
                            response.setMessage(ErrorLog.Success);
                            response.setData(ErrorLog.Success);
                            priority = Priority.LOW;
                        } else {
                            response.setCode(ErrorLog.ODNU1156);
                            response.setMessage(ErrorLog.OrderDetailNotUpdated);
                        }
                    } else {
                        response.setCode(ErrorLog.TDNU1264);
                        response.setMessage(ErrorLog.TransactionDetailNotUpdated);
                    }
                } else {
                    response.setCode(ErrorLog.ODNA1167);
                    response.setMessage(ErrorLog.OrderDetailNotAvailable);
                    priority = Priority.HIGH;
                }
            }
        } catch (Exception e) {
            response.setCode(ErrorLog.CE1109);
            priority = Priority.HIGH;
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        }

        auditLogDao.insertOrderLog(new OrderLogModel(response, requestHeaderModel.getMobile(), null, orderId, priority));
        return response;
    }

    /**************************************************/

    public Response<List<OrderItemListModel>> getOrderByMobile(String mobile, Integer pageNum, Integer pageCount, RequestHeaderModel requestHeaderModel) {
        Response<List<OrderItemListModel>> response = new Response<>();
        Priority priority = Priority.MEDIUM;
        List<TransactionModel> transactionModelList = null;
        List<OrderItemListModel> orderItemListByMobile;

        try {
            if (!utilsDao.validateUser(requestHeaderModel).getCode().equals(ErrorLog.CodeSuccess)) {
                response.setCode(ErrorLog.IH1055);
                response.setMessage(ErrorLog.InvalidHeader);
                priority = Priority.HIGH;
            } else {
                MapSqlParameterSource parameter = new MapSqlParameterSource()
                        .addValue(OrderColumn.mobile, mobile)
                        .addValue(OrderQuery.pageNum, (pageNum - 1) * pageCount)
                        .addValue(OrderQuery.pageCount, pageCount);

                try {
                    transactionModelList = namedParameterJdbcTemplate.query(TransactionQuery.getTransactionByMobile, parameter, TransactionRowMapperLambda.transactionRowMapperLambda);
                } catch (Exception e) {
                    response.setCode(ErrorLog.CE1269);
                    System.err.println(e.getClass().getName() + ": " + e.getMessage());
                }
            }

        } catch (Exception e) {
            response.setCode(ErrorLog.CE1270);
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        } finally {
            if (transactionModelList != null && !transactionModelList.isEmpty()) {
                priority = Priority.LOW;
                response.setCode(ErrorLog.CodeSuccess);
                response.setMessage(ErrorLog.Success);

                orderItemListByMobile = new ArrayList<>();

                for (TransactionModel transactionModel : transactionModelList) {
                    Response<OrderModel> orderModelResponse = getOrderDetailById(transactionModel.getOrderModel().getId());

                    if (!orderModelResponse.getCode().equals(ErrorLog.CodeSuccess)) {
                        priority = Priority.MEDIUM;
                        response.setCode(ErrorLog.ODNA1271);
                        response.setMessage(ErrorLog.OrderDetailNotAvailable);
                        break;
                    } else {
                        transactionModel.setOrderModel(orderModelResponse.getData());
                        Response<ShopModel> shopModelResponse = shopDao.getShopById(transactionModel.getOrderModel().getShopModel().getId());
                        if (!shopModelResponse.getCode().equals(ErrorLog.CodeSuccess)) {
                            priority = Priority.MEDIUM;
                            response.setCode(ErrorLog.SDNA1272);
                            response.setMessage(ErrorLog.ShopDetailNotAvailable);
                            break;
                        } else {
                            shopModelResponse.getData().setPlaceModel(null);
                            transactionModel.getOrderModel().setShopModel(shopModelResponse.getData());
                            Response<List<OrderItemModel>> orderItemsListResponse = itemDao.getItemsByOrderId(transactionModel.getOrderModel());
                            if (!orderItemsListResponse.getCode().equals(ErrorLog.CodeSuccess)) {
                                priority = Priority.MEDIUM;
                                response.setCode(ErrorLog.OIDNA1273);
                                response.setMessage(ErrorLog.OrderItemDetailNotAvailable);
                                break;
                            } else {
                                OrderItemListModel orderItemListModel = new OrderItemListModel();
                                transactionModel.getOrderModel().setUserModel(null);
                                orderItemListModel.setTransactionModel(transactionModel);
                                orderItemListModel.setOrderItemsList(orderItemsListResponse.getData());
                                orderItemListByMobile.add(orderItemListModel);
                            }
                        }
                    }
                }
                response.setData(orderItemListByMobile);
            }
        }

        auditLogDao.insertOrderLog(new OrderLogModel(response, requestHeaderModel.getMobile(), null, mobile + "-" + pageNum, priority));
        return response;
    }

    public Response<List<OrderItemListModel>> getOrderByShopIdPagination(Integer shopId, Integer pageNum, Integer pageCount, RequestHeaderModel requestHeaderModel) {

        Response<List<OrderItemListModel>> response = new Response<>();
        Priority priority = Priority.MEDIUM;
        List<TransactionModel> transactionModelList = null;
        List<OrderItemListModel> orderItemListByMobile;

        try {
            if (requestHeaderModel.getRole().equals((UserRole.CUSTOMER).name())) {
                response.setCode(ErrorLog.IH1056);
                response.setMessage(ErrorLog.InvalidHeader);
                priority = Priority.HIGH;
            } else if (!utilsDao.validateUser(requestHeaderModel).getCode().equals(ErrorLog.CodeSuccess)) {
                response.setCode(ErrorLog.IH1057);
                response.setMessage(ErrorLog.InvalidHeader);
                priority = Priority.HIGH;
            } else {
                MapSqlParameterSource parameter = new MapSqlParameterSource()
                        .addValue(OrderColumn.shopId, shopId)
                        .addValue(OrderQuery.pageNum, (pageNum - 1) * pageCount)
                        .addValue(OrderQuery.pageCount, pageCount);

                try {
                    transactionModelList = namedParameterJdbcTemplate.query(TransactionQuery.getTransactionByShopIdPagination, parameter, TransactionRowMapperLambda.transactionRowMapperLambda);
                } catch (Exception e) {
                    response.setCode(ErrorLog.CE1274);
                    System.err.println(e.getClass().getName() + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            response.setCode(ErrorLog.CE1275);
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        } finally {
            if (transactionModelList != null && !transactionModelList.isEmpty()) {
                priority = Priority.LOW;
                response.setCode(ErrorLog.CodeSuccess);
                response.setMessage(ErrorLog.Success);

                orderItemListByMobile = new ArrayList<>();

                for (TransactionModel transactionModel : transactionModelList) {

                    Response<OrderModel> orderModelResponse = getOrderDetailById(transactionModel.getOrderModel().getId());

                    if (!orderModelResponse.getCode().equals(ErrorLog.CodeSuccess)) {
                        priority = Priority.MEDIUM;
                        response.setCode(ErrorLog.ODNA1276);
                        response.setMessage(ErrorLog.OrderDetailNotAvailable);
                        break;
                    } else {
                        transactionModel.setOrderModel(orderModelResponse.getData());
                        Response<UserModel> userModelResponse = userDao.getUserByMobile(transactionModel.getOrderModel().getUserModel().getMobile());
                        if (!userModelResponse.getCode().equals(ErrorLog.CodeSuccess)) {
                            priority = Priority.MEDIUM;
                            response.setCode(ErrorLog.UDNA1277);
                            response.setMessage(ErrorLog.UserDetailNotAvailable);
                            break;
                        } else {
                            transactionModel.getOrderModel().setUserModel(userModelResponse.getData());
                            Response<List<OrderItemModel>> orderItemsListResponse = itemDao.getItemsByOrderId(transactionModel.getOrderModel());
                            if (!orderItemsListResponse.getCode().equals(ErrorLog.CodeSuccess)) {
                                priority = Priority.MEDIUM;
                                response.setCode(ErrorLog.OIDNA1297);
                                response.setMessage(ErrorLog.OrderItemDetailNotAvailable);
                                break;
                            } else {
                                OrderItemListModel orderItemListModel = new OrderItemListModel();
                                transactionModel.getOrderModel().setShopModel(null);
                                orderItemListModel.setTransactionModel(transactionModel);
                                orderItemListModel.setOrderItemsList(orderItemsListResponse.getData());
                                orderItemListByMobile.add(orderItemListModel);
                            }
                        }
                    }
                }
                response.setData(orderItemListByMobile);
            }
        }

        auditLogDao.insertOrderLog(new OrderLogModel(response, requestHeaderModel.getMobile(), null, mobile + "-" + pageNum, priority));
        return response;
    }

    public Response<List<OrderItemListModel>> getOrderByShopId(Integer shopId, RequestHeaderModel requestHeaderModel) {

        Response<List<OrderItemListModel>> response = new Response<>();
        Priority priority = Priority.MEDIUM;
        List<TransactionModel> transactionModelList = null;
        List<OrderItemListModel> orderItemListByMobile;

        try {
            if (requestHeaderModel.getRole().equals((UserRole.CUSTOMER).name())) {
                response.setCode(ErrorLog.IH1020);
                response.setMessage(ErrorLog.InvalidHeader);
                priority = Priority.HIGH;
            } else if (!utilsDao.validateUser(requestHeaderModel).getCode().equals(ErrorLog.CodeSuccess)) {
                response.setCode(ErrorLog.IH1021);
                response.setMessage(ErrorLog.InvalidHeader);
                priority = Priority.HIGH;
            } else {
                MapSqlParameterSource parameter = new MapSqlParameterSource()
                        .addValue(OrderColumn.shopId, shopId);
                try {
                    transactionModelList = namedParameterJdbcTemplate.query(TransactionQuery.getTransactionByShopId, parameter, TransactionRowMapperLambda.transactionRowMapperLambda);
                } catch (Exception e) {
                    response.setCode(ErrorLog.ODNA1287);
                    response.setMessage(ErrorLog.OrderDetailNotAvailable);
                    System.err.println(e.getClass().getName() + ": " + e.getMessage());
                }
            }

        } catch (Exception e) {
            response.setCode(ErrorLog.CE1289);
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        } finally {

            if (transactionModelList != null && !transactionModelList.isEmpty()) {
                response.setCode(ErrorLog.CodeSuccess);
                response.setMessage(ErrorLog.Success);

                orderItemListByMobile = new ArrayList<>();

                for (TransactionModel transactionModel : transactionModelList) {

                    Response<OrderModel> orderModelResponse = getOrderDetailById(transactionModel.getOrderModel().getId());

                    if (!orderModelResponse.getCode().equals(ErrorLog.CodeSuccess)) {
                        priority = Priority.MEDIUM;
                        response.setCode(ErrorLog.ODNA1291);
                        response.setMessage(ErrorLog.OrderDetailNotAvailable);
                        break;
                    } else {
                        transactionModel.setOrderModel(orderModelResponse.getData());
                        Response<UserModel> userModelResponse = userDao.getUserByMobile(transactionModel.getOrderModel().getUserModel().getMobile());
                        if (!userModelResponse.getCode().equals(ErrorLog.CodeSuccess)) {
                            priority = Priority.MEDIUM;
                            response.setCode(ErrorLog.UDNA1290);
                            response.setMessage(ErrorLog.UserDetailNotAvailable);
                            break;
                        } else {
                            transactionModel.getOrderModel().setUserModel(userModelResponse.getData());
                            Response<List<OrderItemModel>> orderItemsListResponse = itemDao.getItemsByOrderId(transactionModel.getOrderModel());

                            if (!orderItemsListResponse.getCode().equals(ErrorLog.CodeSuccess)) {
                                priority = Priority.MEDIUM;
                                response.setCode(ErrorLog.OIDNA1298);
                                response.setMessage(ErrorLog.OrderItemDetailNotAvailable);
                                break;
                            } else {
                                OrderItemListModel orderItemListModel = new OrderItemListModel();
                                transactionModel.getOrderModel().setShopModel(null);
                                orderItemListModel.setTransactionModel(transactionModel);
                                orderItemListModel.setOrderItemsList(orderItemsListResponse.getData());
                                orderItemListByMobile.add(orderItemListModel);
                            }
                        }
                    }
                }
                response.setData(orderItemListByMobile);
            }
        }

        auditLogDao.insertOrderLog(new OrderLogModel(response, requestHeaderModel.getMobile(), shopId.toString(), shopId.toString(), priority));
        return response;
    }

    public Response<TransactionModel> getOrderById(String orderId, RequestHeaderModel requestHeaderModel) {
        Response<TransactionModel> response = new Response<>();
        TransactionModel transactionModel;
        Priority priority = Priority.MEDIUM;

        try {
            if (!utilsDao.validateUser(requestHeaderModel).getCode().equals(ErrorLog.CodeSuccess)) {
                response.setCode(ErrorLog.IH1058);
                response.setMessage(ErrorLog.InvalidHeader);
                priority = Priority.HIGH;
            } else {
                Response<TransactionModel> transactionModelResponse = transactionDao.getTransactionByOrderId(orderId);

                if (transactionModelResponse.getCode().equals(ErrorLog.CodeSuccess)) {
                    transactionModel = transactionModelResponse.getData();
                    Response<OrderModel> orderModelResponse = getOrderDetailById(orderId);

                    if (orderModelResponse.getCode().equals(ErrorLog.CodeSuccess)) {
                        transactionModel.setOrderModel(orderModelResponse.getData());
                        response.setCode(ErrorLog.CodeSuccess);
                        response.setMessage(ErrorLog.Success);
                        response.setData(transactionModel);
                    } else {
                        response.setCode(orderModelResponse.getCode());
                        response.setMessage(orderModelResponse.getMessage());
                    }
                } else {
                    response.setCode(ErrorLog.TDNA1292);
                    response.setMessage(ErrorLog.TransactionDetailNotAvailable);
                }
            }
        } catch (Exception e) {
            response.setCode(ErrorLog.CE1279);
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        }

        auditLogDao.insertOrderLog(new OrderLogModel(response, requestHeaderModel.getMobile(), id, null, priority));
        return response;
    }

    public Response<OrderModel> getOrderDetailById(String id) {
        Response<OrderModel> response = new Response<>();
        OrderModel orderModel = null;

        try {
            MapSqlParameterSource parameter = new MapSqlParameterSource()
                    .addValue(OrderColumn.id, id);

            try {
                orderModel = namedParameterJdbcTemplate.queryForObject(OrderQuery.getOrderByOrderId, parameter, OrderRowMapperLambda.orderRowMapperLambda);
            } catch (Exception e) {
                response.setCode(ErrorLog.CE1278);
                System.err.println(e.getClass().getName() + ": " + e.getMessage());
            }
        } catch (Exception e) {
            response.setCode(ErrorLog.CE1279);
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        } finally {
            if (orderModel != null) {
                Response<UserModel> userModelResponse = userDao.getUserByMobile(orderModel.getUserModel().getMobile());
                Response<ShopModel> shopModelResponse = shopDao.getShopById(orderModel.getShopModel().getId());

                if (userModelResponse.getCode().equals(ErrorLog.CodeSuccess) && shopModelResponse.getCode().equals(ErrorLog.CodeSuccess)) {
                    orderModel.setUserModel(userModelResponse.getData());
                    orderModel.setShopModel(shopModelResponse.getData());

                    response.setCode(ErrorLog.CodeSuccess);
                    response.setMessage(ErrorLog.Success);
                    response.setData(orderModel);
                } else if (!userModelResponse.getCode().equals(ErrorLog.CodeSuccess)) {
                    response.setCode(ErrorLog.UDNA1293);
                    response.setMessage(ErrorLog.UserDetailNotAvailable);
                } else {
                    response.setCode(ErrorLog.SDNA1294);
                    response.setMessage(ErrorLog.ShopDetailNotAvailable);
                }
            }
        }
        return response;
    }

    /**************************************************/

    public Response<String> updateOrderRating(OrderModel orderModel, RequestHeaderModel requestHeaderModel) {
        Response<String> response = new Response<>();
        Priority priority = Priority.HIGH;

        try {
            if (!utilsDao.validateUser(requestHeaderModel).getCode().equals(ErrorLog.CodeSuccess)) {
                response.setCode(ErrorLog.IH1019);
                response.setData(ErrorLog.InvalidHeader);
            } else {
                MapSqlParameterSource parameter = new MapSqlParameterSource()
                        .addValue(OrderColumn.rating, orderModel.getRating())
                        .addValue(id, orderModel.getId());

                int updateStatus = namedParameterJdbcTemplate.update(OrderQuery.updateOrderRating, parameter);
                if (updateStatus > 0) {
                    ratingDao.updateShopRating(orderModel.getShopModel().getId(), orderModel.getRating());

                    response.setCode(ErrorLog.CodeSuccess);
                    response.setMessage(ErrorLog.Success);
                    response.setData(ErrorLog.Success);
                    priority = Priority.LOW;
                } else {
                    response.setCode(ErrorLog.ODNU1285);
                    response.setData(ErrorLog.OrderDetailNotUpdated);
                }
            }
        } catch (Exception e) {
            response.setCode(ErrorLog.CE1286);
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        }

        auditLogDao.insertOrderLog(new OrderLogModel(response, requestHeaderModel.getMobile(), orderModel.getId(), orderModel.toString(), priority));
        return response;
    }

    public Response<String> updateOrderKey(OrderModel orderModel, RequestHeaderModel requestHeaderModel) {
        Response<String> response = new Response<>();
        Priority priority = Priority.HIGH;

        try {
            if (!utilsDao.validateUser(requestHeaderModel).getCode().equals(ErrorLog.CodeSuccess)) {
                response.setCode(ErrorLog.IH1022);
                response.setMessage(ErrorLog.InvalidHeader);
            } else {
                MapSqlParameterSource parameter = new MapSqlParameterSource()
                        .addValue(OrderColumn.secretKey, orderModel.getSecretKey())
                        .addValue(id, orderModel.getId());

                int updateStatus = namedParameterJdbcTemplate.update(OrderQuery.updateOrderKey, parameter);
                if (updateStatus > 0) {
                    response.setCode(ErrorLog.CodeSuccess);
                    response.setMessage(ErrorLog.Success);
                    response.setData(ErrorLog.Success);
                    priority = Priority.LOW;
                } else {
                    response.setCode(ErrorLog.ODNU1295);
                    response.setMessage(ErrorLog.OrderDetailNotUpdated);
                }
            }
        } catch (Exception e) {
            response.setCode(ErrorLog.CE1296);
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        }

        auditLogDao.insertOrderLog(new OrderLogModel(response, requestHeaderModel.getMobile(), orderModel.getId(), orderModel.toString(), priority));
        return response;
    }

    public Response<String> updateOrderStatus(OrderModel orderModel, RequestHeaderModel requestHeaderModel) {
        Response<String> response = new Response<>();
        Priority priority = Priority.HIGH;

        try {
            if (!utilsDao.validateUser(requestHeaderModel).getCode().equals(ErrorLog.CodeSuccess)) {
                response.setMessage(ErrorLog.InvalidHeader);
            } else {
                Response<TransactionModel> transactionModelResponse = transactionDao.getTransactionByOrderId(orderId);

                if (transactionModelResponse.getCode().equals(ErrorLog.CodeSuccess)) {
                    Response<OrderModel> orderModelResponse = getOrderDetailById(orderModel.getId());
                    if (orderModelResponse.getCode().equals(ErrorLog.CodeSuccess)) {
                        if (checkOrderStatusValidity(orderModelResponse.getData().getOrderStatus(), orderModel.getOrderStatus())) {
                            if (orderModel.getOrderStatus().equals(OrderStatus.READY) || orderModel.getOrderStatus().equals(OrderStatus.OUT_FOR_DELIVERY)) {
                                String secretKey = Integer.toString(100000 + new Random().nextInt(900000));
                                orderModelResponse.getData().setSecretKey(secretKey);
                                Response<String> updateResponse = updateOrderKey(orderModelResponse.getData(), requestHeaderModel);
                                if (!updateResponse.getCode().equals(ErrorLog.CodeSuccess)) {
                                    response.setCode(ErrorLog.ODNU1280);
                                    response.setData(ErrorLog.OrderDetailNotUpdated);
                                }
                            }

                            if (orderModel.getOrderStatus().equals(OrderStatus.COMPLETED) || orderModel.getOrderStatus().equals(OrderStatus.DELIVERED)) {
                                if (!orderModel.getSecretKey().equals(orderModelResponse.getData().getSecretKey())) {
                                    response.setCode(ErrorLog.SKM1281);
                                    response.setData(ErrorLog.SecretKeyMismatch);
                                }
                            }

                            updateOrderStatusQuery(orderModel);
                            response.setCode(ErrorLog.CodeSuccess);
                            response.setMessage(ErrorLog.Success);
                            response.setData(ErrorLog.Success);
                            priority = Priority.LOW;

                        } else {
                            response.setCode(ErrorLog.IOS1282);
                            response.setData(ErrorLog.InvalidOrderStatus);
                        }
                    }
                } else {
                    response.setCode(ErrorLog.ODNA1283);
                    response.setData(ErrorLog.OrderDetailNotAvailable);
                }
            }

        } catch (Exception e) {
            response.setCode(ErrorLog.CE1284);
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        }

        auditLogDao.insertOrderLog(new OrderLogModel(response, requestHeaderModel.getMobile(), orderModel.getId(), orderModel.toString(), priority));
        return response;
    }

    public void updatePendingOrder() {

        /*Response<List<OrderModel>> pendingOrderResponse=getOrdersByStatus(OrderStatus.PENDING);

        if(pendingOrderResponse.getCode().equals(ErrorLog.CodeSuccess)){

            List<OrderModel> orderModelList=pendingOrderResponse.getData();

            if(orderModelList!=null && orderModelList.size()>0){

                for(OrderModel orderModel:orderModelList){

                    OrderStatus newStatus=orderModel.getOrderStatus();
                    TransactionModel transactionModel=getTransactionStatus(orderModel.getId());

                    if(transactionModel.getResponseCode().equals(PaytmResponseLog.TxnSuccessfulCode)){

                        Date currentDate = new Date();
                        long diff = currentDate.getTime() - orderModel.getDate().getTime();
                        long diffMinutes = diff / (60 * 1000) % 60;
                        long diffHours = diff / (60 * 60 * 1000);
                        int diffInDays = (int) ((currentDate.getTime() - orderModel.getDate().getTime()) / (1000 * 60 * 60 * 24));

                        if(diffMinutes>10 || diffHours>=1 || diffInDays>=1)
                        {
                            initiateRefund();
                            newStatus=OrderStatus.TXN_FAILURE;
                        }else{
                            newStatus=OrderStatus.PLACED;
                        }
                    }
                    else if(transactionModel.getResponseCode().equals(PaytmResponseLog.TxnFailureCode)){
                        newStatus=OrderStatus.TXN_FAILURE;
                    }

                    if(newStatus!=orderModel.getOrderStatus()){
                        orderModel.setOrderStatus(newStatus);
                        updateOrderStatusQuery(orderModel);
                        updatePendingTransaction(transactionModel);
                    }
                }
            }
        }*/

    }

    /**************************************************/

    public String verifyPricing(OrderItemListModel orderItemListModel, ConfigurationModel configurationModel) {
        Double deliveryPrice = 0.0;
        OrderModel order = orderItemListModel.getTransactionModel().getOrderModel();

        if (order.getDeliveryPrice() != null) {
            if (configurationModel.getIsDeliveryAvailable() != 1)
                return ErrorLog.DeliveryNotAvailable;

            if (!configurationModel.getDeliveryPrice().equals(order.getDeliveryPrice()))
                return ErrorLog.OrderDeliveryPriceMismatch;

            deliveryPrice = order.getDeliveryPrice();
        }

        Double totalPrice = calculatePricing(orderItemListModel.getOrderItemsList());
        if (totalPrice == null)
            return ErrorLog.ItemPriceMismatch;
        else if (totalPrice == -1.0)
            return ErrorLog.ItemDetailNotAvailable;

        if (totalPrice + deliveryPrice != order.getPrice())
            return ErrorLog.OrderPriceMismatch;

        return ErrorLog.Success;
    }

    public Double calculatePricing(List<OrderItemModel> orderItemModelList) {
        Double totalPrice = 0.0;
        for (OrderItemModel orderItemModel : orderItemModelList) {
            Response<ItemModel> itemModelResponse = itemDao.getItemById(orderItemModel.getItemModel().getId());
            if (!itemModelResponse.getCode().equals(ErrorLog.CodeSuccess))
                return null;
            else if (itemModelResponse.getData().getIsAvailable() == 0)
                return -1.0;
            totalPrice += orderItemModel.getQuantity() * itemModelResponse.getData().getPrice();
        }
        return totalPrice;
    }

    public Response<String> verifyOrderDetails(OrderItemListModel orderItemListModel) {

        Response<String> response = new Response<>();

        try {
            OrderModel order = orderItemListModel.getTransactionModel().getOrderModel();
            ShopModel shopModel = order.getShopModel();

            Response<ConfigurationModel> configurationModelResponse = configurationDao.getConfigurationByShopId(shopModel);
            if (!configurationModelResponse.getCode().equals(ErrorLog.CodeSuccess)) {
                response.setCode(ErrorLog.SDNA1265);
                response.setMessage(ErrorLog.ShopDetailNotAvailable);
            } else {
                ConfigurationModel configurationModel = configurationModelResponse.getData();
                if (configurationModel.getIsOrderTaken() != 1) {
                    response.setCode(ErrorLog.ONT1266);
                    response.setMessage(ErrorLog.OrderNotTaken);
                } else {
                    String deliveryResponse = verifyPricing(orderItemListModel, configurationModel);
                    if (!deliveryResponse.equals(ErrorLog.Success)) {
                        response.setCode(ErrorLog.CE1267);
                        response.setMessage(deliveryResponse);
                        response.setData(deliveryResponse);
                    } else {
                        response.setCode(ErrorLog.CodeSuccess);
                        response.setMessage(ErrorLog.Success);
                        response.setData(configurationModelResponse.getData().getMerchantId());
                    }
                }
            }

        } catch (Exception e) {
            response.setCode(ErrorLog.CE1268);
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        }

        return response;
    }

    public Response<String> initiateTransaction(OrderModel orderModel, String merchantId) {
        Response<String> response = new Response<>();

        String orderId = orderModel.getId();

        //TODO: Implement API to get Transaction Token From Payment Gateway using OrderId & merchantId
        //String transactionToken = getTransactionToken(orderId, merchantId);

        String transactionToken = "12Abdsfds";

        response.setCode(ErrorLog.CodeSuccess);
        response.setMessage(ErrorLog.Success);
        response.setData(transactionToken);
        return response;
    }

    public Response<String> insertOrderItem(OrderItemModel orderItemModel, String orderId) {
        Response<String> response = new Response<>();

        try {
            MapSqlParameterSource parameter = new MapSqlParameterSource()
                    .addValue(OrderItemColumn.orderId, orderId)
                    .addValue(OrderItemColumn.itemId, orderItemModel.getItemModel().getId())
                    .addValue(OrderItemColumn.quantity, orderItemModel.getQuantity())
                    .addValue(OrderItemColumn.price, orderItemModel.getPrice());

            int result = namedParameterJdbcTemplate.update(OrderItemQuery.insertOrderItem, parameter);
            if (result > 0) {
                response.setCode(ErrorLog.CodeSuccess);
                response.setMessage(ErrorLog.Success);
                response.setData(ErrorLog.Success);
            }
        } catch (Exception e) {
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        }
        return response;
    }

    /**************************************************/

    boolean checkOrderStatusValidity(OrderStatus currentStatus, OrderStatus newStatus) {
        // starting states -> failure,pending,placed
        // terminal states -> cancelled by seller or user, delivered, completed

        // pending -> failure ,placed
        // placed  -> cancelled by user or seller , accepted
        // cancelled by user or seller -> refund table entry must be added
        // accepted -> ready, out_for_delivery , cancelled by seller -> refund table entry must be added
        // ready -> secret key must be updated in table, completed
        // out_for_delivery -> secret key must be updated in table, delivered

        if (currentStatus.equals(OrderStatus.PENDING))
            return newStatus.equals(OrderStatus.TXN_FAILURE) || newStatus.equals(OrderStatus.PLACED);
        else if (currentStatus.equals(OrderStatus.PLACED)) {
            return newStatus.equals(OrderStatus.CANCELLED_BY_SELLER) || newStatus.equals(OrderStatus.CANCELLED_BY_USER) || newStatus.equals(OrderStatus.ACCEPTED);
        } else if (currentStatus.equals(OrderStatus.ACCEPTED)) {
            return newStatus.equals(OrderStatus.READY) || newStatus.equals(OrderStatus.OUT_FOR_DELIVERY) || newStatus.equals(OrderStatus.CANCELLED_BY_SELLER);
        } else if (currentStatus.equals(OrderStatus.READY)) {
            return newStatus.equals(OrderStatus.COMPLETED);
        } else if (currentStatus.equals(OrderStatus.OUT_FOR_DELIVERY)) {
            return newStatus.equals(OrderStatus.DELIVERED);
        }
        return false;
    }

    public void updateOrderStatusQuery(OrderModel orderModel) {
        try {
            MapSqlParameterSource parameter = new MapSqlParameterSource()
                    .addValue(status, orderModel.getOrderStatus().name())
                    .addValue(id, orderModel.getId());
            namedParameterJdbcTemplate.update(OrderQuery.updateOrderStatus, parameter);
        } catch (Exception e) {
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        }
    }

    /**************************************************/

    private Response<List<OrderModel>> getOrdersByStatus(OrderStatus orderStatus) {

        Response<List<OrderModel>> response = new Response<>();
        List<OrderModel> orderModelList = null;
        MapSqlParameterSource parameter = new MapSqlParameterSource().addValue(status, orderStatus.name());

        try {
            orderModelList = namedParameterJdbcTemplate.query(OrderQuery.getOrderByStatus, parameter, OrderRowMapperLambda.orderRowMapperLambda);
        } catch (Exception e) {
            response.setCode(ErrorLog.ODNA1299);
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        } finally {
            if (orderModelList != null && !orderModelList.isEmpty()) {
                response.setCode(ErrorLog.CodeSuccess);
                response.setMessage(ErrorLog.Success);
                response.setData(orderModelList);
            }
        }

        return response;
    }

    public void updatePendingTransaction(TransactionModel transactionModel) {
        try {
            MapSqlParameterSource parameter = new MapSqlParameterSource()
                    .addValue(responseCode, transactionModel.getResponseCode())
                    .addValue(responseMessage, transactionModel.getResponseMessage())
                    .addValue(transactionId, transactionModel.getTransactionId());
            namedParameterJdbcTemplate.update(TransactionQuery.updateTransaction, parameter);
        } catch (Exception e) {
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
        }
    }

    public Response<TransactionModel> getTransactionStatus(String orderId) {
        Response<TransactionModel> transactionModelResponse = new Response<>();

        //TODO: GET Transaction Status from Payment Gateway
        TransactionModel transactionModel = new TransactionModel();

        //Populating Dummy Values Here
        transactionModel.setTransactionId("T0002");
        transactionModel.setBankTransactionId("BT0002");
        transactionModel.setTransactionAmount(75.0);
        transactionModel.setCurrency("INR");
        transactionModel.setResponseCode("01");
        transactionModel.setResponseMessage("Success");
        transactionModel.setGatewayName("PAYTM");
        transactionModel.setBankName("HDFC");
        transactionModel.setPaymentMode("UPI");
        transactionModel.setChecksumHash("XXXXX");
        transactionModel.getOrderModel().setId(orderId);

        transactionModelResponse.setCode(ErrorLog.CodeSuccess);
        transactionModelResponse.setMessage(ErrorLog.Success);
        transactionModelResponse.setData(transactionModel);
        return transactionModelResponse;
    }

    public void initiateRefund() {
        // Initiate the refund using payment gateway
    }
}
