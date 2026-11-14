package com.raillink.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * RAILLINK SECURITY CONFIGURATION
 * ═══════════════════════════════════════════════════════════════════════════
 * 
 * This class configures Spring Security for the RailLink Railway Booking System.
 * 
 * DESIGN PATTERNS IMPLEMENTED:
 * 1. Strategy Pattern - Authentication Success Handling
 * 2. Strategy Pattern - Password Encoding
 * 3. Strategy Pattern - Redirect Mechanism (nested)
 * 
 * PURPOSE: Centralizes security configuration and implements role-based
 *          access control for three user types: Admin, Staff, Passenger
 * 
 * @author RailLink Team
 * @version 1.0
 */
@Configuration              // Marks this as a Spring configuration class
@EnableWebSecurity         // Enables Spring Security features
@EnableMethodSecurity      // Allows method-level security (@PreAuthorize, etc.)
public class SecurityConfig {

    // ═══════════════════════════════════════════════════════════════════════
    // STRATEGY PATTERN #1: PASSWORD ENCODING STRATEGY
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Password Encoder Strategy Configuration
     * 
     * STRATEGY PATTERN COMPONENTS:
     * - Strategy Interface: PasswordEncoder (from Spring Security)
     * - Concrete Strategy: BCryptPasswordEncoder
     * - Context: UserService, Authentication Manager
     * 
     * WHY BCrypt?
     * ✓ Industry-standard password hashing
     * ✓ Automatically adds salt (random data for extra security)
     * ✓ One-way encryption (cannot be decrypted)
     * ✓ Slow by design (prevents brute-force attacks)
     * ✓ Adaptive (can increase cost factor over time)
     * 
     * STRATEGY PATTERN BENEFIT:
     * Tomorrow, if we need to upgrade to Argon2, we just change:
     *     return new Argon2PasswordEncoder();
     * No other code changes needed!
     * 
     * HOW IT WORKS:
     * 1. When user registers:
     *    plainPassword = "user123"
     *    encoded = passwordEncoder.encode("user123")
     *    stored in DB = "$2a$10$N9qo8uLO..." (encrypted)
     * 
     * 2. When user logs in:
     *    entered = "user123"
     *    fromDB = "$2a$10$N9qo8uLO..."
     *    matches = passwordEncoder.matches(entered, fromDB)
     *    → returns true if correct
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
        
        // ALTERNATIVE STRATEGIES (commented out):
        // return new Argon2PasswordEncoder();  // More modern
        // return new SCryptPasswordEncoder();  // Memory-intensive
        // return NoOpPasswordEncoder.getInstance();  // NEVER use in production!
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SECURITY FILTER CHAIN CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Security Filter Chain - Main Security Configuration
     * 
     * WHAT IS A FILTER CHAIN?
     * It's a chain of security filters that process each HTTP request:
     * Request → Filter 1 → Filter 2 → ... → Your Controller
     * 
     * Each filter checks:
     * - Is user authenticated?
     * - Does user have required role?
     * - Is CSRF token valid?
     * - etc.
     * 
     * This is an example of CHAIN OF RESPONSIBILITY PATTERN!
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // ─────────────────────────────────────────────────────────────
            // AUTHORIZATION RULES: Who can access what?
            // ─────────────────────────────────────────────────────────────
            .authorizeHttpRequests(authz -> authz
                
                // PUBLIC ENDPOINTS (No authentication required)
                .requestMatchers("/login", "/register").permitAll()
                .requestMatchers("/api/test/**", "/api/health").permitAll()
                .requestMatchers("/api/sse/**").permitAll()
                .requestMatchers("/api/admin/routes").permitAll()
                
                // PASSENGER ENDPOINTS
                // Only users with ROLE_PASSENGER can access
                .requestMatchers("/", "/cssticket").hasRole("PASSENGER")
                .requestMatchers("/api/bookings/**").hasRole("PASSENGER")
                // Note: Spring automatically adds "ROLE_" prefix
                // So hasRole("PASSENGER") checks for "ROLE_PASSENGER"
                
                // ADMIN ENDPOINTS
                // Only users with ROLE_ADMIN can access
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                
                // STAFF ENDPOINTS
                // Both STAFF and ADMIN can access (admins can do staff tasks)
                .requestMatchers("/api/staff/**").hasAnyRole("STAFF","ADMIN")
                
                // AUTHENTICATED ENDPOINTS
                // Any logged-in user can access
                .requestMatchers("/api/profile/**").authenticated()
                
                // DEFAULT RULE
                // Any other request requires authentication
                .anyRequest().authenticated()
            )
            
            // ─────────────────────────────────────────────────────────────
            // FORM LOGIN CONFIGURATION
            // ─────────────────────────────────────────────────────────────
            .formLogin(form -> form
                // Custom login page (not Spring's default)
                .loginPage("/login")
                
                // ★★★ THIS IS WHERE STRATEGY PATTERN IS USED ★★★
                // After successful login, Spring calls our custom strategy
                .successHandler(authenticationSuccessHandler())
                
                // Everyone can access login page (even unauthenticated)
                .permitAll()
            )
            
            // ─────────────────────────────────────────────────────────────
            // LOGOUT CONFIGURATION
            // ─────────────────────────────────────────────────────────────
            .logout(logout -> logout
                // After logout, redirect to home page
                .logoutSuccessUrl("/")
                .permitAll()
            )
            
            // ─────────────────────────────────────────────────────────────
            // CSRF PROTECTION
            // ─────────────────────────────────────────────────────────────
            .csrf(csrf -> csrf.disable());
            // DISABLED for easier development/testing
            // In PRODUCTION, you should ENABLE CSRF protection!
            
        // Build and return the configured filter chain
        return http.build();
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STRATEGY PATTERN #2: AUTHENTICATION SUCCESS HANDLER STRATEGY
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Authentication Success Handler Strategy
     * 
     * ★★★ THIS IS THE MAIN STRATEGY PATTERN IMPLEMENTATION ★★★
     * 
     * PROBLEM:
     * Our railway system has three user roles:
     * - PASSENGER: Regular users who book tickets
     * - STAFF: Employees who manage schedules and bookings
     * - ADMIN: System administrators with full control
     * 
     * Each role should see a different dashboard after login.
     * 
     * SOLUTION:
     * Instead of checking roles in every controller, we create ONE strategy
     * that Spring Security uses automatically after successful login.
     * 
     * STRATEGY PATTERN COMPONENTS:
     * 
     * 1. STRATEGY INTERFACE: AuthenticationSuccessHandler
     *    - Provided by Spring Security
     *    - Defines: onAuthenticationSuccess(request, response, authentication)
     *    - Contract: "What should happen when login succeeds?"
     * 
     * 2. CONCRETE STRATEGY: Our lambda implementation below
     *    - Implements the algorithm: Check role → Determine URL → Redirect
     *    - Encapsulates the role-based routing logic
     * 
     * 3. CONTEXT: Spring Security's form login
     *    - Uses our strategy via .successHandler(authenticationSuccessHandler())
     *    - Calls it automatically after successful authentication
     *    - Doesn't know HOW the strategy works, just THAT it works
     * 
     * BENEFITS:
     * ✓ Centralized: All role-based routing in ONE place
     * ✓ Maintainable: Easy to understand and modify
     * ✓ Testable: Can test independently
     * ✓ Extensible: Easy to add new roles
     * ✓ Flexible: Can change behavior without modifying controllers
     * ✓ Professional: Follows SOLID principles
     * 
     * ALTERNATIVE WITHOUT STRATEGY PATTERN:
     * We would need to check roles in every controller:
     * 
     *     @GetMapping("/dashboard")
     *     public String dashboard(Authentication auth) {
     *         if (hasRole("ADMIN")) return "redirect:/admin/dashboard";
     *         if (hasRole("STAFF")) return "redirect:/staff/dashboard";
     *         return "dashboard";
     *     }
     * 
     * This would be:
     * ✗ Repetitive (same logic in multiple places)
     * ✗ Error-prone (easy to forget to check in one place)
     * ✗ Hard to maintain (change requires modifying multiple files)
     * ✗ Violates DRY (Don't Repeat Yourself)
     */
    @Bean
    public AuthenticationSuccessHandler authenticationSuccessHandler() {
        
        // ─────────────────────────────────────────────────────────────────
        // NESTED STRATEGY PATTERN #3: REDIRECT STRATEGY
        // ─────────────────────────────────────────────────────────────────
        
        /**
         * RedirectStrategy is ANOTHER strategy pattern!
         * 
         * Strategy Interface: RedirectStrategy
         * Concrete Strategy: DefaultRedirectStrategy
         * 
         * WHAT IT DOES:
         * Performs HTTP redirects (sends 302 response to browser)
         * 
         * ALTERNATIVES:
         * - DefaultRedirectStrategy: Standard HTTP 302 redirect
         * - HttpStatusRedirectStrategy: Returns status code without redirect
         * - Custom implementation: Your own redirect logic
         * 
         * WHY STRATEGY HERE?
         * Can switch redirect mechanism without changing our code!
         */
        RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();
        
        // ─────────────────────────────────────────────────────────────────
        // LAMBDA EXPRESSION = SHORT WAY TO IMPLEMENT INTERFACE
        // ─────────────────────────────────────────────────────────────────
        
        /**
         * This lambda implements AuthenticationSuccessHandler interface
         * 
         * LONG WAY (equivalent):
         * 
         *     return new AuthenticationSuccessHandler() {
         *         @Override
         *         public void onAuthenticationSuccess(
         *             HttpServletRequest request,
         *             HttpServletResponse response,
         *             Authentication authentication
         *         ) throws IOException {
         *             // ... code here ...
         *         }
         *     };
         * 
         * SHORT WAY (what we're using):
         *     return (request, response, authentication) -> { ... };
         * 
         * Both do the same thing!
         */
        return (request, response, authentication) -> {
            
            // ═════════════════════════════════════════════════════════════
            // STRATEGY ALGORITHM: ROLE-BASED ROUTING
            // ═════════════════════════════════════════════════════════════
            
            // Step 1: Set default URL (for ROLE_PASSENGER)
            String targetUrl = "/dashboard";
            
            // Step 2: Get user's authorities (roles and permissions)
            // authentication object contains:
            // - username
            // - password (usually null after authentication)
            // - authorities (roles)
            // - authenticated (true/false)
            
            for (GrantedAuthority authority : authentication.getAuthorities()) {
                
                // Step 3: Get role as string
                // Example: "ROLE_ADMIN", "ROLE_STAFF", "ROLE_PASSENGER"
                String role = authority.getAuthority();
                
                // Step 4: Check role and set appropriate URL
                
                if ("ROLE_ADMIN".equals(role)) {
                    // Admin users → Admin Dashboard
                    targetUrl = "/admin/dashboard";
                    // Features: User management, reports, system settings
                    break;  // Stop checking (admin is highest priority)
                    
                } else if ("ROLE_STAFF".equals(role)) {
                    // Staff users → Staff Dashboard
                    targetUrl = "/staff/dashboard";
                    // Features: Manage schedules, view bookings, help passengers
                    break;  // Stop checking (we found the role)
                }
                
                // If neither ADMIN nor STAFF, it's PASSENGER
                // Uses default "/dashboard" (set above)
                // Features: Search trains, book tickets, view bookings
            }
            
            // Step 5: Perform the redirect using our RedirectStrategy
            // This sends HTTP 302 response to browser
            // Browser then loads the target URL
            redirectStrategy.sendRedirect(request, response, targetUrl);
            
            // ═════════════════════════════════════════════════════════════
            // COMPLETE FLOW EXAMPLE:
            // ═════════════════════════════════════════════════════════════
            
            /**
             * SCENARIO 1: Admin Login
             * 1. Admin enters: username=admin, password=admin123
             * 2. Spring Security validates credentials
             * 3. Spring calls this strategy
             * 4. authentication.getAuthorities() = ["ROLE_ADMIN"]
             * 5. Loop finds "ROLE_ADMIN"
             * 6. targetUrl = "/admin/dashboard"
             * 7. redirectStrategy sends 302 to browser
             * 8. Browser loads /admin/dashboard
             * 9. Admin sees: User management, reports, system settings
             * 
             * SCENARIO 2: Passenger Login
             * 1. Passenger enters: username=john, password=pass123
             * 2. Spring Security validates credentials
             * 3. Spring calls this strategy
             * 4. authentication.getAuthorities() = ["ROLE_PASSENGER"]
             * 5. Loop checks ADMIN (no), checks STAFF (no)
             * 6. Uses default: targetUrl = "/dashboard"
             * 7. redirectStrategy sends 302 to browser
             * 8. Browser loads /dashboard
             * 9. Passenger sees: Search trains, book tickets, my bookings
             */
        };
    }
    
    // ═══════════════════════════════════════════════════════════════════════
    // HOW TO ADD A NEW ROLE (EXAMPLE: SUPER_ADMIN)
    // ═══════════════════════════════════════════════════════════════════════
    
    /**
     * Step 1: Add to Role enum (Role.java):
     * 
     *     public enum Role {
     *         ROLE_PASSENGER,
     *         ROLE_ADMIN,
     *         ROLE_STAFF,
     *         ROLE_SUPER_ADMIN  // ← New role
     *     }
     * 
     * Step 2: Update this strategy:
     * 
     *     if ("ROLE_SUPER_ADMIN".equals(role)) {
     *         targetUrl = "/super-admin/dashboard";
     *         break;
     *     } else if ("ROLE_ADMIN".equals(role)) {
     *         ...
     *     }
     * 
     * Step 3: Add security rule:
     * 
     *     .requestMatchers("/api/super-admin/**").hasRole("SUPER_ADMIN")
     * 
     * That's it! No changes needed in controllers or other classes.
     * This is the POWER of Strategy Pattern!
     */
}

// ═══════════════════════════════════════════════════════════════════════════
// END OF FILE
// ═══════════════════════════════════════════════════════════════════════════

/**
 * KEY TAKEAWAYS FOR VIVA:
 * 
 * 1. This class implements Strategy Pattern THREE times:
 *    - Password encoding (BCryptPasswordEncoder)
 *    - Authentication success handling (role-based redirect)
 *    - Redirect mechanism (DefaultRedirectStrategy)
 * 
 * 2. Strategy Pattern = Define family of algorithms, make them interchangeable
 * 
 * 3. Benefits: Flexible, Maintainable, Testable, Extensible
 * 
 * 4. Real-world use: Different roles → Different dashboards
 * 
 * 5. Can explain: Interface + Implementations + Context uses them
 * 
 * 6. Remember: Spring Security is the CONTEXT that uses our STRATEGIES
 */


