package io.github.matthiasmack.opentrans.model

import java.math.BigDecimal

/**
 * The order from `samples/order-spike.xml` (opentrans-optics-xml test resources),
 * built by hand with the generated model. Shared by the model, xml and dsl tests.
 */
val spikeOrder =
    Order(
        type = OrderType.EXPRESS,
        orderHeader =
            OrderHeader(
                controlInfo = ControlInfo(generationDate = DateTime("2026-11-17T09:30:00+01:00")),
                orderInfo =
                    OrderInfo(
                        orderId = "PO-1001",
                        orderDate = DateTime("2026-11-17"),
                        deliveryDate =
                            DeliveryDate(
                                deliveryStartDate = DateTime("2026-11-24"),
                                deliveryEndDate = DateTime("2026-11-28"),
                                type = DeliveryDateType.OPTIONAL,
                            ),
                        parties =
                            listOf(
                                Party(
                                    partyIds =
                                        listOf(
                                            PartyId("BUYER-1", "buyer_specific"),
                                            PartyId("4000000000001", "gln"),
                                        ),
                                    partyRoles = listOf(PartyRole.BUYER, PartyRole.INVOICE_RECIPIENT),
                                    addresses =
                                        listOf(
                                            Address(
                                                names = listOf(MlString("Beispiel GmbH", "deu"), MlString("Example Ltd", "eng")),
                                                streets = listOf(MlString("Musterweg 1")),
                                                zips = listOf(MlString("10000")),
                                                cities = listOf(MlString("Musterstadt")),
                                                countryCoded = "DE",
                                                vatId = "DE000000000",
                                            ),
                                        ),
                                    accounts =
                                        listOf(
                                            Account(
                                                holder = "Example Ltd",
                                                bankAccount = BankAccount("DE00000000000000000000", "iban"),
                                                bankCode = BankCode("XXXXDEXXXXX", "bic"),
                                                bankName = "Example Bank",
                                                bankCountry = "DE",
                                            ),
                                        ),
                                ),
                                Party(
                                    partyIds = listOf(PartyId("SUPPLIER-1", "supplier_specific")),
                                    partyRoles = listOf(PartyRole.SUPPLIER),
                                ),
                            ),
                        orderPartiesReference =
                            OrderPartiesReference(
                                buyerIdref = PartyId("BUYER-1", "buyer_specific"),
                                supplierIdref = PartyId("SUPPLIER-1", "supplier_specific"),
                            ),
                        currency = "EUR",
                        payment =
                            Payment(
                                method =
                                    Card(
                                        type = "VISA",
                                        cardNum = "4111111111111111",
                                        cardExpirationDate = DateTime("2028-05"),
                                        cardHolderName = "card-holder",
                                    ),
                                centralRegulation = false,
                                paymentTerms =
                                    PaymentTerms(
                                        timesForPayment =
                                            listOf(
                                                TimeForPayment(due = Days(10), discount = DiscountFactor(BigDecimal("0.02"))),
                                                TimeForPayment(due = PaymentDate(DateTime("2026-12-31"))),
                                            ),
                                    ),
                            ),
                        termsAndConditions = "general terms",
                        partialShipmentAllowed = true,
                        remarks =
                            listOf(
                                Remark("Bitte an Tor 2 liefern.", type = "general", lang = "deu"),
                                Remark("fragile", type = "transport"),
                            ),
                        headerUdx =
                            XmlContent.parse(
                                """<UDX.CUSTOM xmlns="urn:example:udx"><PROJECT>project-7</PROJECT><PRIORITY level="high"/></UDX.CUSTOM>""",
                            ),
                    ),
            ),
        orderItems =
            listOf(
                OrderItem(
                    lineItemId = "1",
                    productId =
                        ProductId(
                            supplierPid = SupplierPid("SKU-A", "supplier_specific"),
                            buyerPids = listOf(BuyerPid("MAT-100", "buyer_specific")),
                            shortDescriptions = listOf(MlString("Schraube M4", "deu"), MlString("Screw M4", "eng")),
                            longDescriptions = listOf(MlString("Stainless steel screw, M4 x 20", "eng")),
                        ),
                    quantity = BigDecimal("100"),
                    orderUnit = "C62",
                    productPriceFix = ProductPriceFix(BigDecimal("0.15"), priceQuantity = BigDecimal("1")),
                    priceLineAmount = BigDecimal("15.00"),
                    itemUdx = XmlContent.parse("""<UDX.CUSTOM xmlns="urn:example:udx"><COST_CENTER>cc-42</COST_CENTER></UDX.CUSTOM>"""),
                ),
                OrderItem(
                    lineItemId = "2",
                    productId =
                        ProductId(
                            supplierPid = SupplierPid("SKU-B"),
                            shortDescriptions = listOf(MlString("Mutter M4")),
                        ),
                    quantity = BigDecimal("2.5"),
                    orderUnit = "KGM",
                    productPriceFix = ProductPriceFix(BigDecimal("12.40")),
                    priceLineAmount = BigDecimal("31.00"),
                    remarks = listOf(Remark("deliver separately", type = "order")),
                ),
            ),
        orderSummary = OrderSummary(totalItemNum = 2, totalAmount = BigDecimal("46.00")),
    )
