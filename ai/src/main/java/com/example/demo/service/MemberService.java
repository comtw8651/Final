package com.example.demo.service;

import com.example.demo.entity.User;
import com.example.demo.entity.Theme;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class MemberService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ThemeService themeService;
    @Autowired
    private UserThemeService userThemeService;

    // Project1 原有的註冊方法，將被新的驗證碼註冊流程取代，但可保留用於其他情況
    @Transactional
    public boolean register(String email, String password, String username) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM members WHERE email = ?", Integer.class, email);

        if (count != null && count > 0) {
            return false; // Email 已存在
        }

        String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());

        jdbcTemplate.update("INSERT INTO members(email, password, username, coin, current_theme) VALUES (?, ?, ?, ?, ?)",
                email, hashedPassword, username, 100L, "default"); // <--- 這裡設置為 false

        Long userId = jdbcTemplate.queryForObject("SELECT id FROM members WHERE email = ?", Long.class, email);
        Optional<Theme> defaultThemeOptional = themeService.findByThemeName("default");

        if (userId != null && defaultThemeOptional.isPresent()) {
            Theme defaultTheme = defaultThemeOptional.get();
            userThemeService.addUserTheme(userId, defaultTheme.getId());
        } else {
            System.err.println("Error: Default theme not found or user ID could not be retrieved during registration for email: " + email);
            throw new RuntimeException("Registration failed: Could not assign default theme or retrieve user ID.");
        }

        return true;
    }

    @Transactional
    public boolean registerUserAfterVerification(String email, String password, String username) {
        if (findUserByEmail(email).isPresent()) {
            return false; // Email 已存在
        }

        String hashedPassword = BCrypt.hashpw(password, BCrypt.gensalt());

        int rowsAffected = jdbcTemplate.update("INSERT INTO members(email, password, username, coin, current_theme) VALUES (?, ?, ?, ?, ?)",
                email, hashedPassword, username, 100L, "default"); // <--- 這裡設置為 false

        if (rowsAffected > 0) {
            Long userId = jdbcTemplate.queryForObject("SELECT id FROM members WHERE email = ?", Long.class, email);
            Optional<Theme> defaultThemeOptional = themeService.findByThemeName("default");

            if (userId != null && defaultThemeOptional.isPresent()) {
                Theme defaultTheme = defaultThemeOptional.get();
                userThemeService.addUserTheme(userId, defaultTheme.getId());
                return true;
            } else {
                System.err.println("Error: Default theme not found or user ID could not be retrieved during verification registration for email: " + email);
                throw new RuntimeException("Verification registration failed: Could not assign default theme or retrieve user ID.");
            }
        }
        return false;
    }

    // 新增或更新 Google 登入用戶的方法
    @Transactional
    public User saveGoogleUser(String email, String username) {
        Optional<User> existingUserOptional = findUserByEmail(email);

        User user;
        if (existingUserOptional.isPresent()) {
            user = existingUserOptional.get();
            // 可以更新 name，如果 Google 傳回的名字更為準確
            if (!user.getUsername().equals(username)) { // 避免不必要的更新
                 jdbcTemplate.update("UPDATE members SET username = ? WHERE id = ?", username, user.getId());
                 user.setUsername(username);
            }
            System.out.println("🔁 Existing user logged in via Google: " + email);
        } else {
            // 新用戶 → 自動註冊
            user = new User();
            user.setEmail(email);
            user.setUsername(username);
            user.setPassword(null); // Google 登入的用戶，本地沒有密碼，設置為 null
            user.setCoin(100L); // 預設金幣
            user.setCurrentTheme("default"); // 預設主題

            // 使用 JdbcTemplate 插入數據，並獲取生成的主鍵 ID
            // 注意：這裡假設你的 members 表 ID 是自增的
            jdbcTemplate.update("INSERT INTO members(email, password, username, coin, current_theme) VALUES (?, ?, ?, ?, ?)",
                    user.getEmail(), user.getPassword(), user.getUsername(), user.getCoin(), user.getCurrentTheme());

            // 獲取剛剛插入的用戶 ID
            Long userId = jdbcTemplate.queryForObject("SELECT id FROM members WHERE email = ?", Long.class, email);
            user.setId(userId); // 設定 User 物件的 ID

            // 為新用戶新增 'default' 主題的購買記錄
            Optional<Theme> defaultThemeOptional = themeService.findByThemeName("default");
            if (userId != null && defaultThemeOptional.isPresent()) {
                Theme defaultTheme = defaultThemeOptional.get();
                userThemeService.addUserTheme(userId, defaultTheme.getId());
            } else {
                System.err.println("Error: Default theme not found or user ID could not be retrieved during Google registration for email: " + email);
                throw new RuntimeException("Google registration failed: Could not assign default theme or retrieve user ID.");
            }
            System.out.println("✅ New Google user registered: " + email);
        }
        return user;
    }


    public boolean authenticate(String email, String password) {
        try {
            // 首先嘗試獲取整個 User 對象，以便檢查 googleConnected 狀態
            Optional<User> userOptional = findUserByEmail(email);

            if (userOptional.isEmpty()) {
                return false; // Email 不存在
            }

            User user = userOptional.get();

            // 對於非 Google 連接的帳戶，進行 BCrypt 密碼比對
            return BCrypt.checkpw(password, user.getPassword());

        } catch (Exception e) {
            System.err.println("Authentication error for email: " + email + " - " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }


    public String getNameByUsername(String email) {
        try {
            return jdbcTemplate.queryForObject(
                "SELECT username FROM members WHERE email = ?", String.class, email);
        } catch (EmptyResultDataAccessException e) {
            return null; // Email 不存在
        } catch (Exception e) {
            System.err.println("Error getting username for email: " + email + " - " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    // --- 修改：根據 Email 獲取完整的 UserEntity ---
    // 更改了欄位名稱 'theme' 為 'current_theme' 以匹配資料庫和 User 實體
    public Optional<User> findUserByEmail(String email) {
        String sql = "SELECT id, email, password, username, coin, current_theme FROM members WHERE email = ?";
        try {
            User user = jdbcTemplate.queryForObject(sql, new UserRowMapper(), email);
            return Optional.ofNullable(user);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty(); // 用戶不存在
        } catch (Exception e) {
            System.err.println("Error finding user by email: " + email + " - " + e.getMessage());
            e.printStackTrace();
            return Optional.empty();
        }
    }

    // 自定義 RowMapper 來映射 ResultSet 到 User 對象
    private static final class UserRowMapper implements RowMapper<User> {
        @Override
        public User mapRow(ResultSet rs, int rowNum) throws SQLException {
            User userEntity = new User();
            userEntity.setId(rs.getLong("id"));
            userEntity.setEmail(rs.getString("email"));
            userEntity.setPassword(rs.getString("password")); // 允許為 null
            userEntity.setUsername(rs.getString("username"));
            userEntity.setCoin(rs.getLong("coin"));
            userEntity.setCurrentTheme(rs.getString("current_theme"));
            return userEntity;
        }
    }


    // --- 新增方法：更新用戶的金幣 ---
    @Transactional
    public void updateUserCoin(User user) {
        String sql = "UPDATE members SET coin = ? WHERE id = ?";
        int rowsAffected = jdbcTemplate.update(sql, user.getCoin(), user.getId());
        if (rowsAffected == 0) {
            System.err.println("Failed to update coin for user ID: " + user.getId() + ". User not found.");
        }
    }

    // --- 修改：更新用戶的佈景主題 ---
    @Transactional
    public void updateThemeByEmail(String email, String newTheme) {
        String sql = "UPDATE members SET current_theme = ? WHERE email = ?";
        int rowsAffected = jdbcTemplate.update(sql, newTheme, email);
        if (rowsAffected == 0) {
            System.err.println("Failed to update current_theme for user: " + email + ". User not found or no change.");
        }
    }

    @Transactional
    public boolean deleteUserByEmail(String email) {
        Optional<User> userOptional = findUserByEmail(email);
        if (userOptional.isPresent()) {
            User user = userOptional.get();
            jdbcTemplate.update("DELETE FROM user_themes WHERE user_id = ?", user.getId());
            int rows = jdbcTemplate.update("DELETE FROM members WHERE email = ?", email);
            return rows > 0;
        }
        return false;
    }

    public boolean updatePassword(String email, String newPassword) {
    	String hashedPassword = BCrypt.hashpw(newPassword, BCrypt.gensalt());
        int rowsAffected = jdbcTemplate.update("UPDATE members SET password = ? WHERE email = ?" , hashedPassword, email);
        if (rowsAffected == 0) {
             System.err.println("Failed to update password for user: " + email + ". User not found or is Google connected.");
             return false;
        }else {
        	return true;
        }
    }
    public Optional<String> getCurrentThemeNameForUser(String email) {
        return findUserByEmail(email).map(User::getCurrentTheme);
    }
    
    public List<String> getAvailableThemes(String email) {
    	Set<String> ownedThemeNames = new HashSet<>();
        
        Optional<User> userOptional = findUserByEmail(email);
        if (userOptional.isPresent()) {
            User user = userOptional.get();
            Long userId = user.getId();

            // 1. 獲取使用者當前正在使用的主題（即使未明確購買，但也應該在下拉選單中可選）
            if (user.getCurrentTheme() != null && !user.getCurrentTheme().isEmpty()) {
                ownedThemeNames.add(user.getCurrentTheme());
            }

            // 2. 呼叫 UserThemeService 來獲取使用者已「購買」或擁有的主題名稱列表
            List<String> purchasedThemeNames = userThemeService.getPurchasedThemeNamesByUserId(userId);
            ownedThemeNames.addAll(purchasedThemeNames);

            // 3. 確保「default」主題始終可用
            // 這是根據您的業務邏輯來判斷是否所有使用者都擁有 "default" 主題
            //ownedThemeNames.add("default"); 

            // 將 Set 轉換為 List 並返回。HashSet 會自動處理主題名稱的唯一性。
            // 如果需要特定順序，可以在這裡進行排序，例如 new ArrayList<>(ownedThemeNames).sort(Comparator.naturalOrder());
            return new ArrayList<>(ownedThemeNames);
        }
        
        // 如果找不到使用者，或者使用者沒有任何主題，則只返回「default」主題
        // 這是一個安全措施，確保下拉選單不會是空的
        ownedThemeNames.add("default");
        return new ArrayList<>(ownedThemeNames);
    }
    
    public void updateUserCurrentTheme(String email, String themeName) {
        int rowsAffected = jdbcTemplate.update("UPDATE members SET current_theme = ? WHERE email = ?", themeName, email);
        if (rowsAffected == 0) {
            System.err.println("Failed to update current_theme for user: " + email + ". User not found or no change.");
        }
    }
}
