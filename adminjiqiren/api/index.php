<?php
declare(strict_types=1);

header('Content-Type: application/json; charset=utf-8');
header('Cache-Control: no-store');

require_once __DIR__ . '/Db.php';
require_once __DIR__ . '/Auth.php';
require_once __DIR__ . '/ReportRepository.php';

$configFile = is_file(__DIR__ . '/config.local.php')
    ? __DIR__ . '/config.local.php'
    : __DIR__ . '/config.example.php';
$config = require $configFile;

$sessionDir = __DIR__ . '/storage/sessions';
$auth = new Auth($sessionDir, (int) ($config['session_ttl'] ?? 86400));

function json_out(int $http, int $code, string $message, mixed $data = null): void
{
    http_response_code($http);
    echo json_encode(
        ['code' => $code, 'message' => $message, 'data' => $data],
        JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES
    );
    exit;
}

function read_json_body(): array
{
    $raw = file_get_contents('php://input') ?: '';
    if ($raw === '') {
        return [];
    }
    $data = json_decode($raw, true);
    return is_array($data) ? $data : [];
}

function bearer_token(): ?string
{
    $header = $_SERVER['HTTP_AUTHORIZATION'] ?? $_SERVER['REDIRECT_HTTP_AUTHORIZATION'] ?? '';
    if (preg_match('/^Bearer\s+(\S+)$/i', $header, $m)) {
        return $m[1];
    }
    return null;
}

function route_path(): string
{
    if (isset($_GET['route']) && is_string($_GET['route'])) {
        return trim($_GET['route'], '/');
    }
    $uri = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/';
    $marker = '/adminjiqiren/api/';
    $pos = strpos($uri, $marker);
    if ($pos === false) {
        $marker = '/api/';
        $pos = strpos($uri, $marker);
    }
    if ($pos === false) {
        return '';
    }
    $path = substr($uri, $pos + strlen($marker));
    $path = explode('?', $path, 2)[0];
    if ($path === 'index.php' || str_starts_with($path, 'index.php/')) {
        $path = substr($path, strlen('index.php'));
        $path = ltrim($path, '/');
    }
    return trim($path, '/');
}

$method = strtoupper($_SERVER['REQUEST_METHOD'] ?? 'GET');
$route = route_path();

try {
    if ($method === 'OPTIONS') {
        json_out(204, 0, 'ok', null);
    }

    if ($method === 'POST' && $route === 'login') {
        $body = read_json_body();
        $username = trim((string) ($body['username'] ?? ''));
        $password = (string) ($body['password'] ?? '');
        $result = $auth->login($username, $password);
        if ($result === null) {
            json_out(401, 401, '账号或密码错误', null);
        }
        json_out(200, 0, 'ok', $result);
    }

    if ($method === 'POST' && $route === 'logout') {
        $auth->requireUser(bearer_token());
        $token = bearer_token();
        if ($token !== null) {
            $auth->logout($token);
        }
        json_out(200, 0, 'ok', null);
    }

    $auth->requireUser(bearer_token());
    $pdo = Db::pdo($config['db']);
    $repo = new ReportRepository($pdo);

    if ($method === 'GET' && $route === 'machines') {
        json_out(200, 0, 'ok', ['items' => $repo->listMachines()]);
    }

    if ($method === 'GET' && $route === 'report/current') {
        $machineCode = isset($_GET['machineCode']) ? trim((string) $_GET['machineCode']) : null;
        $keyword = isset($_GET['keyword']) ? trim((string) $_GET['keyword']) : null;
        json_out(200, 0, 'ok', $repo->currentReport($machineCode ?: null, $keyword ?: null));
    }

    if ($method === 'GET' && $route === 'report/daily') {
        $startDate = trim((string) ($_GET['startDate'] ?? ''));
        $endDate = trim((string) ($_GET['endDate'] ?? ''));
        if ($startDate === '' || $endDate === '') {
            json_out(400, 400, 'DATE_RANGE_REQUIRED', null);
        }
        if (!preg_match('/^\d{4}-\d{2}-\d{2}$/', $startDate) || !preg_match('/^\d{4}-\d{2}-\d{2}$/', $endDate)) {
            json_out(400, 400, 'INVALID_DATE', null);
        }
        if ($endDate < $startDate) {
            json_out(400, 400, 'INVALID_DATE_RANGE', null);
        }
        $start = new DateTimeImmutable($startDate);
        $end = new DateTimeImmutable($endDate);
        $days = (int) $start->diff($end)->days;
        if ($days > 92) {
            json_out(400, 400, 'DATE_RANGE_TOO_LARGE', null);
        }
        $machineCode = isset($_GET['machineCode']) ? trim((string) $_GET['machineCode']) : null;
        $keyword = isset($_GET['keyword']) ? trim((string) $_GET['keyword']) : null;
        json_out(200, 0, 'ok', $repo->dailyReport($startDate, $endDate, $machineCode ?: null, $keyword ?: null));
    }

    if ($method === 'GET' && $route === 'report/updown') {
        $startDate = trim((string) ($_GET['startDate'] ?? ''));
        $endDate = trim((string) ($_GET['endDate'] ?? ''));
        if ($startDate === '' || $endDate === '') {
            json_out(400, 400, 'DATE_RANGE_REQUIRED', null);
        }
        if (!preg_match('/^\d{4}-\d{2}-\d{2}$/', $startDate) || !preg_match('/^\d{4}-\d{2}-\d{2}$/', $endDate)) {
            json_out(400, 400, 'INVALID_DATE', null);
        }
        if ($endDate < $startDate) {
            json_out(400, 400, 'INVALID_DATE_RANGE', null);
        }
        $start = new DateTimeImmutable($startDate);
        $end = new DateTimeImmutable($endDate);
        $days = (int) $start->diff($end)->days;
        if ($days > 92) {
            json_out(400, 400, 'DATE_RANGE_TOO_LARGE', null);
        }
        $machineCode = isset($_GET['machineCode']) ? trim((string) $_GET['machineCode']) : null;
        $keyword = isset($_GET['keyword']) ? trim((string) $_GET['keyword']) : null;
        json_out(200, 0, 'ok', $repo->updownReport($startDate, $endDate, $machineCode ?: null, $keyword ?: null));
    }

    if ($method === 'GET' && $route === 'report/agents') {
        $machineCode = isset($_GET['machineCode']) ? trim((string) $_GET['machineCode']) : null;
        $keyword = isset($_GET['keyword']) ? trim((string) $_GET['keyword']) : null;
        json_out(200, 0, 'ok', $repo->agentTeamReport($machineCode ?: null, $keyword ?: null));
    }

    if ($method === 'GET' && $route === 'report/agents/descendants') {
        $machineCode = isset($_GET['machineCode']) ? trim((string) $_GET['machineCode']) : '';
        $agentUserId = isset($_GET['agentUserId']) ? trim((string) $_GET['agentUserId']) : '';
        if ($machineCode === '' || $agentUserId === '') {
            json_out(400, 400, 'MISSING_PARAMS', null);
        }
        $scope = isset($_GET['scope']) ? trim((string) $_GET['scope']) : 'all';
        if ($scope === '') {
            $scope = 'all';
        }
        if ($scope !== 'all' && $scope !== 'direct') {
            json_out(400, 400, 'INVALID_SCOPE', null);
        }
        $data = $repo->agentDescendants($machineCode, $agentUserId, $scope);
        if ($data === null) {
            json_out(404, 404, 'AGENT_NOT_FOUND', null);
        }
        json_out(200, 0, 'ok', $data);
    }

    json_out(404, 404, 'NOT_FOUND', null);
} catch (AuthException $e) {
    json_out($e->status(), $e->status(), $e->getMessage(), null);
} catch (Throwable $e) {
    json_out(500, 500, 'SERVER_ERROR', null);
}
