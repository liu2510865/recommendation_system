package xiaowu.backed.interfaces.rest;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import xiaowu.backed.interfaces.security.JwtTokenProvider;

/**
 * 认证控制器 —— 登录获取 JWT 令牌
 *
 * <p>当前为简化版：内置单用户，生产环境应替换为数据库用户表。
 *
 * @author xiaowu
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    @Value("${security.auth.username:admin}")
    private String authUsername;

    @Value("${security.auth.password:admin123}")
    private String authPassword;

    @PostMapping("/login")
    public ResponseEntity<Map<String, String>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");

        if (authUsername.equals(username) && authPassword.equals(password)) {
            String token = jwtTokenProvider.generateToken(username);
            return ResponseEntity.ok(Map.of(
                    "token", token,
                    "tokenType", "Bearer",
                    "expiresIn", "86400"));
        }

        return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
    }
}
