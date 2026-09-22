<?php
declare(strict_types=1);

final class Auth
{
    private const USERNAME = 'admin';
    private const PASSWORD = 'alun8888';

    public function __construct(
        private readonly string $sessionDir,
        private readonly int $ttl
    ) {
        if (!is_dir($this->sessionDir)) {
            mkdir($this->sessionDir, 0750, true);
        }
    }

    public function login(string $username, string $password): ?array
    {
        if ($username !== self::USERNAME || $password !== self::PASSWORD) {
            return null;
        }
        $token = bin2hex(random_bytes(32));
        $exp = time() + $this->ttl;
        $payload = ['user' => self::USERNAME, 'exp' => $exp];
        file_put_contents(
            $this->sessionFile($token),
            json_encode($payload, JSON_UNESCAPED_UNICODE),
            LOCK_EX
        );
        return ['token' => $token, 'expiresAt' => gmdate('c', $exp)];
    }

    public function logout(string $token): void
    {
        $file = $this->sessionFile($token);
        if (is_file($file)) {
            unlink($file);
        }
    }

    public function requireUser(?string $token): string
    {
        if ($token === null || $token === '') {
            throw new AuthException('UNAUTHORIZED', 401);
        }
        $file = $this->sessionFile($token);
        if (!is_file($file)) {
            throw new AuthException('UNAUTHORIZED', 401);
        }
        $raw = file_get_contents($file);
        $data = json_decode($raw ?: '', true);
        if (!is_array($data) || !isset($data['exp'], $data['user'])) {
            @unlink($file);
            throw new AuthException('UNAUTHORIZED', 401);
        }
        if ((int) $data['exp'] < time()) {
            @unlink($file);
            throw new AuthException('UNAUTHORIZED', 401);
        }
        return (string) $data['user'];
    }

    private function sessionFile(string $token): string
    {
        if (!preg_match('/^[a-f0-9]{64}$/', $token)) {
            throw new AuthException('UNAUTHORIZED', 401);
        }
        return $this->sessionDir . '/' . $token . '.json';
    }
}

final class AuthException extends RuntimeException
{
    public function __construct(
        string $message,
        private readonly int $status
    ) {
        parent::__construct($message);
    }

    public function status(): int
    {
        return $this->status;
    }
}
