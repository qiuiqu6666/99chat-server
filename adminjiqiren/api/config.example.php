<?php
declare(strict_types=1);

/**
 * Copy to config.local.php and fill ROBOT_DB_* values.
 */
return [
    'db' => [
        'host' => '127.0.0.1',
        'port' => 3306,
        'name' => 'jiqiren',
        'user' => 'jiqiren',
        'pass' => 'CHANGE_ME',
        'charset' => 'utf8mb4',
    ],
    'session_ttl' => 86400,
];
