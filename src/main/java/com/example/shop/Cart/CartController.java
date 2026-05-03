package com.example.shop.Cart;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import com.example.shop.Accessoire.Accessoire;
import com.example.shop.Order.Order;
import com.example.shop.Order.OrderRepository;
import com.example.shop.Product.InsufficientStockException;
import com.example.shop.Product.Product;
import com.example.shop.Product.ProductRepository;
import com.example.shop.Smartphone.Smartphone;
import com.example.shop.User.User;
import com.example.shop.Payment.StripeService;

import java.util.Optional;

/**
 * Contrôleur gérant les interactions avec le panier (affichage, ajout,
 * suppression, code promo).
 */
@Controller
@RequestMapping("/basket")
public class CartController {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(CartController.class);

    @Autowired
    private CartService cartService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private StripeService stripeService;

    @Autowired
    private com.example.shop.Payment.PaymentRepository paymentRepository;

    @GetMapping
    public String showBasket(Model model, HttpSession session, @AuthenticationPrincipal User user) {
        model.addAttribute("user", user);
        java.util.List<Cart> cartItems = cartService.getItems(user);
        model.addAttribute("cartItems", cartItems);

        // Logique de recommandation
        java.util.Set<Accessoire> recommendedAccessories = new java.util.HashSet<>();
        java.util.Set<Long> cartProductIds = cartItems.stream()
                .map(item -> item.getProduct().getId())
                .collect(java.util.stream.Collectors.toSet());

        for (Cart item : cartItems) {
            if (item.getProduct() instanceof Smartphone) {
                Smartphone smartphone = (Smartphone) item.getProduct();
                if (smartphone.getAccessoires() != null) {
                    for (Accessoire acc : smartphone.getAccessoires()) {
                        // Ajouter seulement si pas déjà dans le panier
                        if (!cartProductIds.contains(acc.getId())) {
                            recommendedAccessories.add(acc);
                        }
                    }
                }
            }
        }
        model.addAttribute("recommendedAccessories", recommendedAccessories);

        // Calculer et ajouter les prix avec réductions
        calculateAndAddPrices(user, session, model);

        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("d MMMM",
                java.util.Locale.FRENCH);
        java.time.LocalDate today = java.time.LocalDate.now();
        java.time.LocalDate deliveryDate = today.plusDays(3);

        model.addAttribute("dateToday", today.format(formatter));
        model.addAttribute("dateDelivery", deliveryDate.format(formatter));

        return "basket";
    }

    @PostMapping("/applyPromo")
    public String applyPromo(@RequestParam String code, HttpSession session) {
        if ("JEE".equalsIgnoreCase(code)) {
            session.setAttribute("promoCode", "JEE");
        } else {
            session.removeAttribute("promoCode");
        }
        return "redirect:/basket";
    }

    @PostMapping("/add")
    public String addToCart(@RequestParam Long productId, @RequestParam int quantity,
            @AuthenticationPrincipal User user,
            org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {
        Optional<Product> productOpt = productRepository.findById(productId);
        if (productOpt.isPresent()) {
            try {
                cartService.addProduct(user, productOpt.get(), quantity);
            } catch (InsufficientStockException e) {
                redirectAttributes.addFlashAttribute("error", e.getMessage());
            }
        }

        return "redirect:/basket";
    }

    @GetMapping("/remove/{productId}")
    public String removeFromCart(@PathVariable Long productId, @AuthenticationPrincipal User user) {
        Optional<Product> productOpt = productRepository.findById(productId);
        if (productOpt.isPresent()) {
            cartService.removeProduct(user, productOpt.get());
        }

        return "redirect:/basket";
    }

    @GetMapping("/decrease/{productId}")
    public String decreaseQuantity(@PathVariable Long productId, @AuthenticationPrincipal User user) {
        Optional<Product> productOpt = productRepository.findById(productId);
        if (productOpt.isPresent()) {
            cartService.decreaseQuantity(user, productOpt.get());
        }

        return "redirect:/basket";
    }

    @PostMapping("/updateInsurance")
    public String updateInsurance(@RequestParam Long productId, @RequestParam InsuranceType insuranceType,
            @AuthenticationPrincipal User user) {
        cartService.updateInsurance(user, productId, insuranceType);
        return "redirect:/basket";
    }

    @GetMapping("/checkout")
    public String checkout(Model model, HttpSession session, @AuthenticationPrincipal User user) {
        model.addAttribute("user", user);
        java.util.List<Cart> cartItems = cartService.getItems(user);

        if (cartItems.isEmpty()) {
            return "redirect:/basket";
        }

        model.addAttribute("cartItems", cartItems);

        // Calculer et ajouter les prix avec réductions
        calculateAndAddPrices(user, session, model);

        return "checkout";
    }

    @PostMapping("/confirm")
    public String confirmOrder(
            @jakarta.validation.Valid Order order,
            org.springframework.validation.BindingResult result,
            @RequestParam String paymentMethod,
            HttpSession session,
            @AuthenticationPrincipal User user,
            org.springframework.web.servlet.mvc.support.RedirectAttributes redirectAttributes) {

        if (result.hasErrors()) {
            redirectAttributes.addFlashAttribute("error", result.getAllErrors().get(0).getDefaultMessage());
            return "redirect:/basket/checkout";
        }

        try {
            // Calculer le total final avec réductions
            double totalPrice = calculateFinalPrice(user, session);
            logger.info("Tentative de commande pour l'utilisateur {} - Total: {} - Méthode: {}", user.getEmail(), totalPrice, paymentMethod);

            if ("CREDIT_CARD".equals(paymentMethod)) {
                // Créer une commande en attente de paiement
                Order savedOrder = cartService.createOrder(user, order.getFirstName(), order.getLastName(), order.getAddress(), order.getCity(), order.getZip(), order.getCountry(),
                        paymentMethod, totalPrice, "PENDING_PAYMENT");
                
                // Préparer Stripe Session
                String baseUrl = org.springframework.web.servlet.support.ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
                String successUrl = baseUrl + "/basket/checkout/success/" + savedOrder.getId() + "?session_id={CHECKOUT_SESSION_ID}";
                String cancelUrl = baseUrl + "/basket/checkout?error=payment_cancelled";
                
                logger.info("Création de la session Stripe pour la commande {}", savedOrder.getId());
                com.stripe.model.checkout.Session stripeSession = stripeService.createCheckoutSession(user, cartService.getItems(user), totalPrice, successUrl, cancelUrl);
                
                // Enregistrer les détails du paiement
                com.example.shop.Payment.Payment payment = new com.example.shop.Payment.Payment();
                payment.setOrder(savedOrder);
                payment.setStripeSessionId(stripeSession.getId());
                payment.setAmount(totalPrice);
                payment.setCurrency("eur");
                payment.setStatus("PENDING");
                paymentRepository.save(payment);

                session.removeAttribute("promoCode");
                session.removeAttribute("tradeInDiscount");
                
                logger.info("Redirection vers Stripe: {}", stripeSession.getUrl());
                return "redirect:" + stripeSession.getUrl();
            } else {
                Order savedOrder = cartService.createOrder(user, order.getFirstName(), order.getLastName(), order.getAddress(), order.getCity(), order.getZip(), order.getCountry(),
                        paymentMethod, totalPrice, "CONFIRMED");
                cartService.clearCart(user); // Vider le panier immédiatement pour COD
                session.removeAttribute("promoCode");
                session.removeAttribute("tradeInDiscount");
                return "redirect:/basket/checkout/success/" + savedOrder.getId();
            }
        } catch (Exception e) {
            logger.error("Erreur lors de la confirmation de commande", e);
            redirectAttributes.addFlashAttribute("error", "Erreur lors de la commande: " + e.getMessage());
            return "redirect:/basket/checkout";
        }
    }

    @PostMapping("/trade-in")
    public String estimateTradeIn(@RequestParam int year, @RequestParam String condition, HttpSession session) {
        int currentYear = java.time.LocalDate.now().getYear();

        // Validation de l'année
        if (year < 2000 || year > currentYear) {
            // Année invalide, ne pas appliquer de réduction
            return "redirect:/basket";
        }

        // Valeur de base
        double baseValue = 100.0;

        // Facteur d'état
        double conditionFactor;
        switch (condition) {
            case "NEUF":
                conditionFactor = 2.0; // Bonus x2 pour du neuf
                break;
            case "BON":
                conditionFactor = 1.2; // Bonus x1.2 pour du bon état
                break;
            case "MAUVAIS":
                conditionFactor = 0.8; // Malus réduit pour mauvais état
                break;
            default:
                conditionFactor = 1.0; // État moyen par défaut
                break;
        }

        // Facteur d'année
        int age = currentYear - year;
        double yearFactor = Math.max(0.3, 1.0 - (age * 0.05));

        double discount = baseValue * conditionFactor * yearFactor;

        // Arrondir à 2 décimales
        discount = Math.round(discount * 100.0) / 100.0;

        session.setAttribute("tradeInDiscount", discount);

        return "redirect:/basket";
    }

    @GetMapping("/removeTradeIn")
    public String removeTradeIn(HttpSession session) {
        session.removeAttribute("tradeInDiscount");
        return "redirect:/basket";
    }

    @GetMapping("/checkout/success/{orderId}")
    public String orderSuccess(@PathVariable Long orderId, @RequestParam(required = false) String session_id,
            Model model, HttpSession session, @AuthenticationPrincipal User user) {
        java.util.Optional<Order> orderOpt = orderRepository.findById(orderId);
        if (orderOpt.isPresent()) {
            Order order = orderOpt.get();
            // Vérification de sécurité : s'assurer que la commande appartient à
            // l'utilisateur
            if (order.getUser().getId().equals(user.getId())) {
                if (session_id != null) {
                    // Update payment status if it exists
                    paymentRepository.findByStripeSessionId(session_id).ifPresent(p -> {
                        p.setStatus("SUCCEEDED");
                        paymentRepository.save(p);
                    });
                    
                    order.setStatus("PAID");
                    orderRepository.save(order);
                    cartService.clearCart(user); // Vider le panier après paiement réussi
                }
                model.addAttribute("orderId", orderId);
                model.addAttribute("order", order);
                return "order_confirmation";
            }
        }

        return "redirect:/";
    }

    /**
     * Calcule le prix final avec les réductions (trade-in et code promo).
     */
    private double calculateFinalPrice(User user, HttpSession session) {
        double totalPrice = cartService.getTotal(user);

        // Logique de reprise (Trade-in)
        Double tradeInDiscount = (Double) session.getAttribute("tradeInDiscount");
        if (tradeInDiscount != null) {
            totalPrice -= tradeInDiscount;
        }
        if (totalPrice < 0)
            totalPrice = 0.0;

        // Logique de code promo
        String promoCode = (String) session.getAttribute("promoCode");
        if ("JEE".equals(promoCode)) {
            totalPrice = totalPrice * 0.9;
        }

        return totalPrice;
    }

    /**
     * Calcule les prix avec réductions et les ajoute au modèle.
     */
    private void calculateAndAddPrices(User user, HttpSession session, Model model) {
        double totalPrice = cartService.getTotal(user);

        // Logique de reprise
        Double tradeInDiscount = (Double) session.getAttribute("tradeInDiscount");
        if (tradeInDiscount != null) {
            model.addAttribute("tradeInDiscount", tradeInDiscount);
            totalPrice -= tradeInDiscount;
        }
        if (totalPrice < 0)
            totalPrice = 0.0;

        model.addAttribute("totalPrice", totalPrice);

        // Logique de code promo
        String promoCode = (String) session.getAttribute("promoCode");
        if ("JEE".equals(promoCode)) {
            double discountedPrice = totalPrice * 0.9;
            model.addAttribute("discountedPrice", String.format("%.2f", discountedPrice));
            model.addAttribute("promoApplied", true);
        } else {
            model.addAttribute("promoApplied", false);
        }
    }
}
