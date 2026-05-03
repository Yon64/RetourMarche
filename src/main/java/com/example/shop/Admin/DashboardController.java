package com.example.shop.Admin;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.example.shop.Accessoire.Accessoire;
import com.example.shop.Accessoire.AccessoireRepository;
import com.example.shop.Accessoire.TypeAccessoire;
import com.example.shop.Order.Order;
import com.example.shop.Order.OrderItem;
import com.example.shop.Order.OrderRepository;
import com.example.shop.Product.Product;
import com.example.shop.Product.ProductController;
import com.example.shop.Product.ProductRepository;
import com.example.shop.Smartphone.Smartphone;
import com.example.shop.Smartphone.SmartphoneRepository;
import com.example.shop.User.User;
import com.example.shop.User.UserRepository;

import jakarta.servlet.http.HttpSession;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contrôleur pour le tableau de bord administrateur (gestion produits,
 * utilisateurs, stocks).
 */
@Controller
public class DashboardController {

    private static final Logger logger = LoggerFactory.getLogger(DashboardController.class);
    private static final int PAGE_SIZE = 5;

    @Autowired
    private SmartphoneRepository smartphoneRepository;

    @Autowired
    private AccessoireRepository accessoireRepository;

    @Autowired
    private UserRepository usersRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private OrderRepository orderRepository;

    @GetMapping("/dashboard")
    public String dashboard(@RequestParam(required = false, defaultValue = "") String section,
            @RequestParam(required = false, defaultValue = "") String query,
            @RequestParam(required = false, defaultValue = "") String userQuery,
            @RequestParam(defaultValue = "0") int page,
            Model model,
            HttpSession session) {

        //vérifier le rôle de l'utilisateur stocké en session : pas connecté ou pas ADMIN redirigé à la page login
        User user = (User) session.getAttribute("user");
        if (user == null || !"ADMIN".equals(user.getRole())) {
            return "redirect:/login";
        }

        model.addAttribute("user", user);
        model.addAttribute("section", section);
        model.addAttribute("query", query);
        model.addAttribute("userQuery", userQuery);

        //configure la pagination de taille 5 éléments par page
        Pageable pageable = PageRequest.of(page, PAGE_SIZE, Sort.by("id").ascending());

        //section selectionné du dashboard de gestion parmi les 3 
        //affiche les pages correspondantes
        switch (section) {
            case "delete":
                // On récupère les smartphones correspondant à la recherche.
                // La recherche s'applique sur le nom ou la marque.
                Page<Smartphone> smartphonesPage = smartphoneRepository
                        .findByNameContainingIgnoreCaseOrBrandContainingIgnoreCase(query, query, pageable);
                model.addAttribute("smartphones", smartphonesPage);
                break;

            case "deleteAccessoire":
                Page<Accessoire> accessoiresPage;

                //recherche est vide, on force findAll() pour afficher tous les accessoires.
                if (query.isBlank()) {
                    accessoiresPage = accessoireRepository.findAll(pageable);
                } else {
                    // Sinon, on filtre uniquement sur le nom.
                    accessoiresPage = accessoireRepository.findByNameContainingIgnoreCase(query, pageable);
                }

                model.addAttribute("accessoires", accessoiresPage);
                break;

            case "users":
                Page<User> usersPage = usersRepository
                        .findByNomContainingIgnoreCaseOrPrenomContainingIgnoreCase(userQuery, userQuery, pageable);
                model.addAttribute("users", usersPage);
                break;

            default:
                // Aucune section sélectionnée par défaut
                break;
        }

        model.addAttribute("types", TypeAccessoire.values());
        model.addAttribute("couleurs", ProductController.getColorMap());

        return "dashboard";
    }

    //modifier le stock si on l'on est connecté en ADMIN
    @org.springframework.web.bind.annotation.PostMapping("/updateStock")
    public String updateStock(@RequestParam Long id, @RequestParam int stock,
            @RequestParam(defaultValue = "delete") String section, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null || !"ADMIN".equals(user.getRole())) {
            return "redirect:/";
        }

        java.util.Optional<Product> productOpt = productRepository.findById(id);
        if (productOpt.isPresent()) {
            Product product = productOpt.get();
            product.setStock(stock);
            productRepository.save(product);
        }

        return "redirect:/dashboard?section=" + section;
    }

    //modifier un smartphone si on l'on est connecté en ADMIN
    @org.springframework.web.bind.annotation.PostMapping("/updateSmartphone")
    public String updateSmartphone(@RequestParam Long id, @RequestParam String name, @RequestParam String brand,
            @RequestParam Double price, @RequestParam Double newPrice, @RequestParam Integer stock,
            HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null || !"ADMIN".equals(user.getRole())) {
            return "redirect:/";
        }

        Smartphone smartphone = smartphoneRepository.findById(id).orElse(null);
        if (smartphone != null) {
            smartphone.setName(name);
            smartphone.setBrand(brand);
            smartphone.setPrice(price);
            smartphone.setNewPrice(newPrice);
            smartphone.setStock(stock);
            smartphoneRepository.save(smartphone);
        }

        return "redirect:/dashboard?section=delete";
    }

    //modifier un accessoire si on l'on est connecté en ADMIN
    @org.springframework.web.bind.annotation.PostMapping("/updateAccessoire")
    public String updateAccessoire(@RequestParam Long id, @RequestParam String name, @RequestParam TypeAccessoire type,
            @RequestParam Double price, @RequestParam Integer stock, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null || !"ADMIN".equals(user.getRole())) {
            return "redirect:/";
        }

        Accessoire accessoire = accessoireRepository.findById(id).orElse(null);
        if (accessoire != null) {
            accessoire.setName(name);
            accessoire.setType(type);
            accessoire.setPrice(price);
            accessoire.setStock(stock);
            accessoireRepository.save(accessoire);
        }

        return "redirect:/dashboard?section=deleteAccessoire";
    }

    //modifier un USER si on l'on est connecté en ADMIN
    @org.springframework.web.bind.annotation.PostMapping("/updateUser")
    public String updateUser(@RequestParam Long id, @RequestParam String nom, @RequestParam String prenom,
            @RequestParam String email, @RequestParam String telephone, @RequestParam String role,
            HttpSession session) {
        User currentUser = (User) session.getAttribute("user");
        if (currentUser == null || !"ADMIN".equals(currentUser.getRole())) {
            return "redirect:/login";
        }

        User userToEdit = usersRepository.findById(id).orElse(null);
        if (userToEdit != null) {
            userToEdit.setNom(nom);
            userToEdit.setPrenom(prenom);
            userToEdit.setEmail(email);
            userToEdit.setTelephone(telephone);
            userToEdit.setRole(role);
            usersRepository.save(userToEdit);
        }

        return "redirect:/dashboard?section=users";
    }

    //supprimer un smartphone si la personne qui le fait est bien admin
    //on le supprime avec son ID
    @GetMapping("/deleteSmartphone/{id}")
    @Transactional
    public String deleteSmartphone(@PathVariable Long id, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null || !"ADMIN".equals(user.getRole())) {
            return "redirect:/";
        }
        try {
            // Récupérer le smartphone
            java.util.Optional<Smartphone> smartphoneOpt = smartphoneRepository.findById(id);
            if (smartphoneOpt.isPresent()) {
                Smartphone smartphone = smartphoneOpt.get();

                // Vérifier si ce produit a été commandé
                java.util.List<Order> orders = orderRepository.findAll();
                boolean hasBeenOrdered = false;
                for (Order order : orders) {
                    for (OrderItem item : order.getItems()) {
                        if (item.getProduct().getId().equals(id)) {
                            hasBeenOrdered = true;
                            break;
                        }
                    }
                    if (hasBeenOrdered)
                        break;
                }

                if (hasBeenOrdered) {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Cannot delete smartphone with ID {} - it has been ordered", id);
                    }
                    return "redirect:/dashboard?section=delete&error=product_ordered";
                }

                // Retirer ce smartphone de tous les accessoires qui le référencent
                java.util.List<Accessoire> accessoires = accessoireRepository.findAll();
                for (Accessoire accessoire : accessoires) {
                    if (accessoire.getSmartphones() != null && accessoire.getSmartphones().contains(smartphone)) {
                        accessoire.getSmartphones().remove(smartphone);
                        accessoireRepository.save(accessoire);
                    }
                }

                // Maintenant on peut supprimer le smartphone
                smartphoneRepository.deleteById(id);
                if (logger.isInfoEnabled()) {
                    logger.info("Smartphone with ID {} deleted successfully by user {}", id, user.getEmail());
                }
            }
        } catch (Exception e) {
            if (logger.isErrorEnabled()) {
                logger.error("Error deleting smartphone with ID {}: {}", id, e.getMessage(), e);
            }
            // Gérer la violation de contrainte de clé étrangère
            return "redirect:/dashboard?section=delete&error=cannot_delete";
        }
        return "redirect:/dashboard?section=delete";
    }

    //supprimer un accessoire si la personne qui le fait est bien admin
    //on le supprime avec son ID
    @GetMapping("/deleteAccessoire/{id}")
    @Transactional
    public String deleteAccessoire(@PathVariable Long id, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null || !"ADMIN".equals(user.getRole())) {
            return "redirect:/";
        }
        try {
            accessoireRepository.deleteById(id);
            if (logger.isInfoEnabled()) {
                logger.info("Accessoire with ID {} deleted successfully by user {}", id, user.getEmail());
            }
        } catch (Exception e) {
            if (logger.isErrorEnabled()) {
                logger.error("Error deleting accessoire with ID {}: {}", id, e.getMessage(), e);
            }
            return "redirect:/dashboard?section=deleteAccessoire&error=cannot_delete";
        }
        return "redirect:/dashboard?section=deleteAccessoire";
    }

    //supprimer un user si la personne qui le fait est bien admin
    //on le supprime avec son ID
    @GetMapping("/deleteUser/{id}")
    public String deleteUser(@PathVariable Long id, HttpSession session) {
        User user = (User) session.getAttribute("user");
        if (user == null || !"ADMIN".equals(user.getRole())) {
            return "redirect:/";
        }
        try {
            usersRepository.deleteById(id);
            if (logger.isInfoEnabled()) {
                logger.info("User with ID {} deleted successfully by admin {}", id, user.getEmail());
            }
        } catch (Exception e) {
            if (logger.isErrorEnabled()) {
                logger.error("Error deleting user with ID {}: {}", id, e.getMessage(), e);
            }
            return "redirect:/dashboard?section=users&error=cannot_delete";
        }
        return "redirect:/dashboard?section=users";
    }

}