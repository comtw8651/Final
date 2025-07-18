package com.example.demo.controller;

import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import com.example.demo.entity.Theme;
import com.example.demo.entity.User;
import com.example.demo.service.MemberService;
import com.example.demo.service.ThemeService;
import com.example.demo.service.UserThemeService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Base64; // <-- 新增這一行：導入 Base64 類

@Controller
public class ProfileController {

    private static final Logger logger = LoggerFactory.getLogger(ProfileController.class);

    @Autowired
    private MemberService memberService;

    @Autowired
    private ThemeService themeService;
    @Autowired
    private UserThemeService userThemeService;

    @GetMapping("/welcome")
    public String welcomePage(HttpSession session, Model model) {
        String email = (String) session.getAttribute("email");
        if (email == null) {
            return "redirect:/login";
        }

        try {
            User currentUser = memberService.findUserByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

            model.addAttribute("username", currentUser.getUsername());
            model.addAttribute("currentTheme", currentUser.getCurrentTheme());
            
            List<String> userPurchasedThemeNames = userThemeService.getPurchasedThemeNamesByUserId(currentUser.getId());
            model.addAttribute("availableThemes", userPurchasedThemeNames);

            
        } catch (Exception e) {
            session.invalidate();
            return "redirect:/login?error=auth";
        }

        return "welcome"; // 對應你目前畫面，只有歡迎、導覽列、按鈕
    }
    
    @GetMapping("/shop")
    public String shopPage(HttpSession session, Model model) {
        String email = (String) session.getAttribute("email");
        if (email == null) {
            return "redirect:/login";
        }

        try {
            User currentUser = memberService.findUserByEmail(email)
                .orElseThrow(() -> new RuntimeException("User not found"));

            model.addAttribute("username", currentUser.getUsername());
            model.addAttribute("userCoin", currentUser.getCoin());
            model.addAttribute("currentTheme", currentUser.getCurrentTheme());

            List<String> userPurchasedThemeNames = userThemeService.getPurchasedThemeNamesByUserId(currentUser.getId());
            model.addAttribute("availableThemes", userPurchasedThemeNames);

            List<Theme> allShopThemes = themeService.getAllThemes();

            // ****** 以下是新增的邏輯來處理圖片 Base64 編碼和 Data URL ******
            for (Theme theme : allShopThemes) {
                if (theme.getPage() != null && theme.getPage().length > 0) {
                    String base64Image = Base64.getEncoder().encodeToString(theme.getPage());
                    // 請注意：這裡假設所有圖片都是 JPEG。
                    // 如果您的圖片類型可能不同（例如有 PNG），
                    // 則您需要在資料庫中儲存圖片的 MIME 類型，並在這裡動態判斷。
                    // 範例：如果確定是 PNG，則應該是 "data:image/png;base64,"
                    String dataUrl = "data:image/jpeg;base64," + base64Image;
                    
                    // 將 Base64 數據 URL 設置到 Theme 對象的一個「非持久化」屬性中
                    // 這要求您的 Theme Entity 類中需要新增一個 transient 字段和對應的 getter/setter
                    theme.setBase64PageDataUrl(dataUrl); // <-- 假設 Theme 類有這個 setter
                } else {
                    theme.setBase64PageDataUrl(""); // 如果沒有圖片數據，設置為空字串或默認圖片
                }
            }
            // ***************************************************************

            model.addAttribute("allThemes", allShopThemes);

        } catch (Exception e) {
            logger.error("Error in shopPage for user {}: {}", email, e.getMessage(), e); // 記錄完整的堆棧信息
            session.invalidate();
            return "redirect:/login?error=shop";
        }

        return "shop"; // 對應商城頁面
    }


    // 處理前端 AJAX 請求，保存用戶選擇的佈景主題
    @PostMapping("/saveTheme")
    @ResponseBody
    public Map<String, String> saveTheme(@RequestBody Map<String, String> payload,
                                         HttpSession session) {

        String email = (String) session.getAttribute("email");
        if (email == null) {
            logger.warn("Unauthorized attempt to save theme: no active session.");
            return createErrorResponse("請先登入。");
        }

        String selectedThemeName = payload.get("theme");
        if (selectedThemeName == null || selectedThemeName.isEmpty()) {
            logger.warn("Save theme request from {} with missing theme name.", email);
            return createErrorResponse("主題名稱不能為空。");
        }

        try {
            User currentUser = memberService.findUserByEmail(email)
                                                  .orElseThrow(() -> new RuntimeException("User not found."));

            // 檢查用戶是否已擁有此主題 (包括預設主題)
            boolean hasPurchased = userThemeService.hasUserPurchasedTheme(currentUser.getId(), selectedThemeName);

            if (!hasPurchased) {
                logger.warn("User {} attempted to use unpurchased theme: {}", email, selectedThemeName);
                return createErrorResponse("您尚未擁有此主題，請先購買。");
            }

            // 更新用戶當前主題
            memberService.updateThemeByEmail(email, selectedThemeName);
            logger.info("User {} successfully updated theme to {}.", email, selectedThemeName);
            return createSuccessResponse("佈景主題已成功更新。");

        } catch (Exception e) {
            logger.error("Failed to save theme for user {}: {}", email, e.getMessage());
            return createErrorResponse("更新佈景主題失敗，請稍後再試。");
        }
    }

    // 處理購買主題的 AJAX 請求
    @PostMapping("/buyTheme")
    @ResponseBody
    public Map<String, Object> buyTheme(@RequestBody Map<String, String> payload,
                                        HttpSession session) {
        String email = (String) session.getAttribute("email");
        if (email == null) {
            logger.warn("Unauthorized attempt to buy theme: no active session.");
            return createErrorResponseMap("請先登入。");
        }

        String themeName = payload.get("theme");
        if (themeName == null || themeName.isEmpty()) {
            logger.warn("Buy theme request from {} with missing theme name.", email);
            return createErrorResponseMap("主題名稱不能為空。");
        }

        try {
            User currentUser = memberService.findUserByEmail(email)
                                                  .orElseThrow(() -> new RuntimeException("User not found."));

            Theme themeToBuy = themeService.findByThemeName(themeName)
                                            .orElseThrow(() -> new RuntimeException("主題不存在。"));

            if (userThemeService.hasUserPurchasedTheme(currentUser.getId(), themeName)) {
                logger.warn("User {} attempted to repurchase theme: {}", email, themeName);
                return createErrorResponseMap("您已擁有此主題。");
            }

            if (currentUser.getCoin() < themeToBuy.getPrice()) {
                logger.warn("User {} has insufficient coin to buy theme {}. Current coin: {}, Theme price: {}",
                            email, themeName, currentUser.getCoin(), themeToBuy.getPrice());
                return createErrorResponseMap("金幣不足，無法購買。");
            }

            // 扣除金幣並記錄購買
            currentUser.setCoin(currentUser.getCoin() - themeToBuy.getPrice());
            memberService.updateUserCoin(currentUser); // 更新用戶金幣

            userThemeService.addUserTheme(currentUser.getId(), themeToBuy.getId()); // 保存購買記錄

            logger.info("User {} successfully bought theme {}. New coin balance: {}",
                        email, themeName, currentUser.getCoin());

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("message", "購買成功！");
            response.put("newCoin", currentUser.getCoin()); // 返回新的金幣餘額給前端更新
            return response;

        } catch (Exception e) {
            logger.error("Failed to buy theme for user {}: {}", email, e.getMessage());
            return createErrorResponseMap("購買主題失敗，請稍後再試。");
        }
    }

    // 輔助方法：創建成功的 JSON 回應
    private Map<String, String> createSuccessResponse(String message) {
        Map<String, String> response = new HashMap<>();
        response.put("status", "success");
        response.put("message", message);
        return response;
    }

    // 輔助方法：創建錯誤的 JSON 回應 (String value)
    private Map<String, String> createErrorResponse(String message) {
        Map<String, String> response = new HashMap<>();
        response.put("status", "error");
        response.put("message", message);
        return response;
    }

    // 輔助方法：創建錯誤的 JSON 回應 (Object value for buyTheme)
    private Map<String, Object> createErrorResponseMap(String message) {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "error");
        response.put("message", message);
        return response;
    }
}