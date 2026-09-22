<?php
declare(strict_types=1);

final class ReportRepository
{
    public function __construct(private readonly PDO $pdo)
    {
    }

    /** @return list<array{machineCode: string}> */
    public function listMachines(): array
    {
        $sql = <<<'SQL'
SELECT DISTINCT s.player_group_id AS machineCode
FROM robot_player_snapshot s
INNER JOIN robot_runtime_state r
  ON r.player_group_id = s.player_group_id
 AND r.database_generation = s.database_generation
WHERE s.active = 1
ORDER BY s.player_group_id ASC
SQL;
        $rows = $this->pdo->query($sql)->fetchAll();
        $out = [];
        foreach ($rows as $row) {
            $out[] = ['machineCode' => (string) $row['machineCode']];
        }
        return $out;
    }

    public function currentReport(?string $machineCode, ?string $keyword): array
    {
        $where = ['s.active = 1'];
        $params = [];
        if ($machineCode !== null && $machineCode !== '') {
            $where[] = 's.player_group_id = ?';
            $params[] = $machineCode;
        }
        if ($keyword !== null && $keyword !== '') {
            $where[] = '(s.wxid LIKE ? OR s.player_no LIKE ? OR IFNULL(s.nickname, \'\') LIKE ? OR IFNULL(s.display_name, \'\') LIKE ?)';
            $like = '%' . $keyword . '%';
            array_push($params, $like, $like, $like, $like);
        }
        $whereSql = implode(' AND ', $where);
        $sql = <<<SQL
SELECT
  s.player_group_id AS machineCode,
  s.wxid AS userId,
  s.player_no AS playerNo,
  s.nickname AS nickname,
  s.display_name AS displayName,
  s.balance AS balance,
  s.total_up AS totalUp,
  s.total_down AS totalDown,
  s.total_flow AS totalFlow,
  s.total_profit_loss AS totalProfitLoss,
  s.total_rebate AS totalRebate,
  s.rebate_rate AS rebateRate,
  s.direct_parent_wxid AS directParentUserId,
  s.level_no AS levelNo
FROM robot_player_snapshot s
INNER JOIN robot_runtime_state r
  ON r.player_group_id = s.player_group_id
 AND r.database_generation = s.database_generation
WHERE {$whereSql}
ORDER BY s.player_group_id ASC, s.wxid ASC
SQL;
        $stmt = $this->pdo->prepare($sql);
        $stmt->execute($params);
        $items = [];
        $summary = $this->emptySummary();
        foreach ($stmt->fetchAll() as $row) {
            $item = $this->mapMoneyRow($row, [
                'machineCode', 'userId', 'playerNo', 'nickname', 'displayName',
                'balance', 'totalUp', 'totalDown', 'totalFlow', 'totalProfitLoss',
                'totalRebate', 'rebateRate', 'directParentUserId', 'levelNo',
            ]);
            $item['levelNo'] = (int) ($row['levelNo'] ?? 0);
            $items[] = $item;
            $this->addSummary($summary, $item);
        }
        $summary['userCount'] = count($items);
        return ['summary' => $summary, 'items' => $items];
    }

    public function dailyReport(string $startDate, string $endDate, ?string $machineCode, ?string $keyword): array
    {
        $where = ['d.business_date BETWEEN ? AND ?'];
        $params = [$startDate, $endDate];
        if ($machineCode !== null && $machineCode !== '') {
            $where[] = 'd.player_group_id = ?';
            $params[] = $machineCode;
        }
        if ($keyword !== null && $keyword !== '') {
            $where[] = '(d.wxid LIKE ? OR d.player_no LIKE ? OR IFNULL(d.nickname, \'\') LIKE ? OR IFNULL(d.display_name, \'\') LIKE ?)';
            $like = '%' . $keyword . '%';
            array_push($params, $like, $like, $like, $like);
        }
        $whereSql = implode(' AND ', $where);
        $sql = <<<SQL
SELECT
  d.business_date AS businessDate,
  d.player_group_id AS machineCode,
  d.wxid AS userId,
  d.player_no AS playerNo,
  d.nickname AS nickname,
  d.display_name AS displayName,
  d.total_up AS totalUp,
  d.total_down AS totalDown,
  d.total_flow AS totalFlow,
  d.total_profit_loss AS totalProfitLoss,
  d.total_rebate AS totalRebate,
  d.balance AS balance,
  d.direct_parent_wxid AS directParentUserId
FROM robot_player_daily_summary d
WHERE {$whereSql}
ORDER BY d.business_date ASC, d.player_group_id ASC, d.wxid ASC
SQL;
        $stmt = $this->pdo->prepare($sql);
        $stmt->execute($params);
        $items = [];
        $summary = [
            'userCount' => 0,
            'rowCount' => 0,
            'totalUp' => '0.0000',
            'totalDown' => '0.0000',
            'totalFlow' => '0.0000',
            'totalProfitLoss' => '0.0000',
            'totalRebate' => '0.0000',
        ];
        $users = [];
        foreach ($stmt->fetchAll() as $row) {
            $item = $this->mapMoneyRow($row, [
                'businessDate', 'machineCode', 'userId', 'playerNo', 'nickname', 'displayName',
                'totalUp', 'totalDown', 'totalFlow', 'totalProfitLoss', 'totalRebate',
                'balance', 'directParentUserId',
            ]);
            if ($item['businessDate'] instanceof DateTimeInterface) {
                $item['businessDate'] = $item['businessDate']->format('Y-m-d');
            } else {
                $item['businessDate'] = substr((string) $item['businessDate'], 0, 10);
            }
            $items[] = $item;
            $users[$item['machineCode'] . "\0" . $item['userId']] = true;
            $summary['totalUp'] = $this->addDec($summary['totalUp'], $item['totalUp']);
            $summary['totalDown'] = $this->addDec($summary['totalDown'], $item['totalDown']);
            $summary['totalFlow'] = $this->addDec($summary['totalFlow'], $item['totalFlow']);
            $summary['totalProfitLoss'] = $this->addDec($summary['totalProfitLoss'], $item['totalProfitLoss']);
            $summary['totalRebate'] = $this->addDec($summary['totalRebate'], $item['totalRebate']);
        }
        $summary['userCount'] = count($users);
        $summary['rowCount'] = count($items);
        return [
            'startDate' => $startDate,
            'endDate' => $endDate,
            'summary' => $summary,
            'items' => $items,
        ];
    }

    public function updownReport(string $startDate, string $endDate, ?string $machineCode, ?string $keyword): array
    {
        $startTs = strtotime($startDate . ' 00:00:00');
        $endTs = strtotime($endDate . ' 23:59:59');
        $where = ['u.approved_at BETWEEN ? AND ?'];
        $params = [$startTs, $endTs];
        if ($machineCode !== null && $machineCode !== '') {
            $where[] = 'u.player_group_id = ?';
            $params[] = $machineCode;
        }
        if ($keyword !== null && $keyword !== '') {
            $where[] = '(u.wxid LIKE ? OR IFNULL(u.player_no, \'\') LIKE ? OR IFNULL(u.nickname, \'\') LIKE ?)';
            $like = '%' . $keyword . '%';
            array_push($params, $like, $like, $like);
        }
        $whereSql = implode(' AND ', $where);
        $sql = <<<SQL
SELECT
  u.approved_at AS approvedAt,
  u.player_group_id AS machineCode,
  u.player_no AS playerNo,
  u.wxid AS userId,
  u.nickname AS nickname,
  u.direction AS direction,
  u.amount AS amount,
  u.balance_after AS balanceAfter,
  u.record_id AS recordId
FROM robot_player_updown_record u
WHERE {$whereSql}
ORDER BY u.approved_at DESC, u.id DESC
SQL;
        $stmt = $this->pdo->prepare($sql);
        $stmt->execute($params);
        $items = [];
        $summary = [
            'rowCount' => 0,
            'totalUp' => '0.0000',
            'totalDown' => '0.0000',
        ];
        foreach ($stmt->fetchAll() as $row) {
            $direction = (string) ($row['direction'] ?? '');
            $amount = $this->fmtDec($row['amount'] ?? null);
            $item = [
                'approvedAt' => (int) ($row['approvedAt'] ?? 0),
                'machineCode' => (string) ($row['machineCode'] ?? ''),
                'playerNo' => $row['playerNo'] === null ? '' : (string) $row['playerNo'],
                'userId' => (string) ($row['userId'] ?? ''),
                'nickname' => $row['nickname'] === null ? '' : (string) $row['nickname'],
                'direction' => $direction,
                'amount' => $amount,
                'balanceAfter' => $this->fmtDec($row['balanceAfter'] ?? null),
                'recordId' => (string) ($row['recordId'] ?? ''),
            ];
            $items[] = $item;
            if ($direction === 'UP') {
                $summary['totalUp'] = $this->addDec($summary['totalUp'], $amount);
            } elseif ($direction === 'DOWN') {
                $summary['totalDown'] = $this->addDec($summary['totalDown'], $amount);
            }
        }
        $summary['rowCount'] = count($items);
        return [
            'startDate' => $startDate,
            'endDate' => $endDate,
            'summary' => $summary,
            'items' => $items,
        ];
    }

    public function agentTeamReport(?string $machineCode, ?string $keyword): array
    {
        $where = [
            's.active = 1',
            "s.player_type <> '1'",
            's.rebate_rate > 0',
        ];
        $params = [];
        if ($machineCode !== null && $machineCode !== '') {
            $where[] = 's.player_group_id = ?';
            $params[] = $machineCode;
        }
        if ($keyword !== null && $keyword !== '') {
            $where[] = '(s.wxid LIKE ? OR s.player_no LIKE ? OR IFNULL(s.nickname, \'\') LIKE ? OR IFNULL(s.display_name, \'\') LIKE ?)';
            $like = '%' . $keyword . '%';
            array_push($params, $like, $like, $like, $like);
        }
        $whereSql = implode(' AND ', $where);
        $sql = <<<SQL
SELECT
  s.player_group_id AS machineCode,
  s.player_no AS playerNo,
  s.wxid AS userId,
  s.nickname AS nickname,
  s.display_name AS displayName,
  s.level_no AS levelNo,
  s.rebate_rate AS rebateRate,
  s.balance AS balance,
  s.total_rebate AS totalRebate,
  s.direct_parent_wxid AS directParentUserId,
  (
    SELECT COUNT(*)
    FROM robot_player_snapshot c
    WHERE c.player_group_id = s.player_group_id
      AND c.database_generation = s.database_generation
      AND c.active = 1
      AND c.player_type <> '1'
      AND c.direct_parent_wxid = s.wxid
  ) AS directChildCount,
  (
    SELECT COUNT(*)
    FROM robot_player_snapshot d
    WHERE d.player_group_id = s.player_group_id
      AND d.database_generation = s.database_generation
      AND d.active = 1
      AND d.player_type <> '1'
      AND d.wxid <> s.wxid
      AND (
        d.direct_parent_wxid = s.wxid
        OR d.parent_path LIKE CONCAT('%', s.wxid, '###%')
      )
  ) AS descendantCount
FROM robot_player_snapshot s
INNER JOIN robot_runtime_state r
  ON r.player_group_id = s.player_group_id
 AND r.database_generation = s.database_generation
WHERE {$whereSql}
ORDER BY s.level_no ASC, s.player_group_id ASC, s.wxid ASC
SQL;
        $stmt = $this->pdo->prepare($sql);
        $stmt->execute($params);
        $items = [];
        $level1AgentCount = 0;
        foreach ($stmt->fetchAll() as $row) {
            $levelNo = (int) ($row['levelNo'] ?? 0);
            if ($levelNo === 1) {
                $level1AgentCount++;
            }
            $items[] = [
                'machineCode' => (string) ($row['machineCode'] ?? ''),
                'playerNo' => $row['playerNo'] === null ? '' : (string) $row['playerNo'],
                'userId' => (string) ($row['userId'] ?? ''),
                'nickname' => $row['nickname'] === null ? '' : (string) $row['nickname'],
                'displayName' => $row['displayName'] === null ? '' : (string) $row['displayName'],
                'levelNo' => $levelNo,
                'rebateRate' => $this->fmtDec($row['rebateRate'] ?? null),
                'balance' => $this->fmtDec($row['balance'] ?? null),
                'totalRebate' => $this->fmtDec($row['totalRebate'] ?? null),
                'directParentUserId' => $row['directParentUserId'] === null ? '' : (string) $row['directParentUserId'],
                'directChildCount' => (int) ($row['directChildCount'] ?? 0),
                'descendantCount' => (int) ($row['descendantCount'] ?? 0),
            ];
        }
        return [
            'summary' => [
                'agentCount' => count($items),
                'level1AgentCount' => $level1AgentCount,
            ],
            'items' => $items,
        ];
    }

    /** @return array<string, mixed>|null */
    public function agentDescendants(string $machineCode, string $agentUserId, string $scope): ?array
    {
        $agentSql = <<<'SQL'
SELECT
  s.wxid AS userId,
  s.player_no AS playerNo,
  s.nickname AS nickname,
  s.display_name AS displayName,
  s.level_no AS levelNo,
  s.rebate_rate AS rebateRate
FROM robot_player_snapshot s
INNER JOIN robot_runtime_state r
  ON r.player_group_id = s.player_group_id
 AND r.database_generation = s.database_generation
WHERE s.player_group_id = ?
  AND s.wxid = ?
  AND s.active = 1
LIMIT 1
SQL;
        $agentStmt = $this->pdo->prepare($agentSql);
        $agentStmt->execute([$machineCode, $agentUserId]);
        $agent = $agentStmt->fetch();
        if ($agent === false) {
            return null;
        }

        $where = [
            'd.active = 1',
            "d.player_type <> '1'",
            'd.wxid <> ?',
            'd.player_group_id = ?',
        ];
        $params = [$agentUserId, $machineCode];
        if ($scope === 'direct') {
            $where[] = 'd.direct_parent_wxid = ?';
            $params[] = $agentUserId;
        } else {
            $where[] = '(d.direct_parent_wxid = ? OR d.parent_path LIKE CONCAT(\'%\', ?, \'###%\'))';
            $params[] = $agentUserId;
            $params[] = $agentUserId;
        }
        $whereSql = implode(' AND ', $where);
        $sql = <<<SQL
SELECT
  d.player_no AS playerNo,
  d.wxid AS userId,
  d.nickname AS nickname,
  d.display_name AS displayName,
  d.level_no AS levelNo,
  d.rebate_rate AS rebateRate,
  d.direct_parent_wxid AS directParentUserId,
  d.direct_parent_no AS directParentNo,
  d.balance AS balance,
  d.total_up AS totalUp,
  d.total_down AS totalDown,
  d.total_flow AS totalFlow,
  d.total_profit_loss AS totalProfitLoss,
  d.total_rebate AS totalRebate
FROM robot_player_snapshot d
INNER JOIN robot_runtime_state r
  ON r.player_group_id = d.player_group_id
 AND r.database_generation = d.database_generation
WHERE {$whereSql}
ORDER BY d.level_no ASC, d.wxid ASC
SQL;
        $stmt = $this->pdo->prepare($sql);
        $stmt->execute($params);
        $items = [];
        foreach ($stmt->fetchAll() as $row) {
            $nickname = $row['nickname'] === null ? '' : (string) $row['nickname'];
            $displayName = $row['displayName'] === null ? '' : (string) $row['displayName'];
            $items[] = [
                'playerNo' => $row['playerNo'] === null ? '' : (string) $row['playerNo'],
                'userId' => (string) ($row['userId'] ?? ''),
                'nickname' => $nickname,
                'displayName' => $displayName,
                'levelNo' => (int) ($row['levelNo'] ?? 0),
                'rebateRate' => $this->fmtDec($row['rebateRate'] ?? null),
                'directParentUserId' => $row['directParentUserId'] === null ? '' : (string) $row['directParentUserId'],
                'directParentNo' => $row['directParentNo'] === null ? '' : (string) $row['directParentNo'],
                'balance' => $this->fmtDec($row['balance'] ?? null),
                'totalUp' => $this->fmtDec($row['totalUp'] ?? null),
                'totalDown' => $this->fmtDec($row['totalDown'] ?? null),
                'totalFlow' => $this->fmtDec($row['totalFlow'] ?? null),
                'totalProfitLoss' => $this->fmtDec($row['totalProfitLoss'] ?? null),
                'totalRebate' => $this->fmtDec($row['totalRebate'] ?? null),
            ];
        }

        $agentNickname = $agent['nickname'] === null ? '' : (string) $agent['nickname'];
        if ($agentNickname === '') {
            $agentNickname = $agent['displayName'] === null ? '' : (string) $agent['displayName'];
        }

        return [
            'machineCode' => $machineCode,
            'agentUserId' => $agentUserId,
            'agentPlayerNo' => $agent['playerNo'] === null ? '' : (string) $agent['playerNo'],
            'agentNickname' => $agentNickname,
            'scope' => $scope,
            'total' => count($items),
            'items' => $items,
        ];
    }

    private function emptySummary(): array
    {
        return [
            'userCount' => 0,
            'totalUp' => '0.0000',
            'totalDown' => '0.0000',
            'totalFlow' => '0.0000',
            'totalProfitLoss' => '0.0000',
            'totalBalance' => '0.0000',
            'totalRebate' => '0.0000',
        ];
    }

    private function addSummary(array &$summary, array $item): void
    {
        $summary['totalUp'] = $this->addDec($summary['totalUp'], $item['totalUp']);
        $summary['totalDown'] = $this->addDec($summary['totalDown'], $item['totalDown']);
        $summary['totalFlow'] = $this->addDec($summary['totalFlow'], $item['totalFlow']);
        $summary['totalProfitLoss'] = $this->addDec($summary['totalProfitLoss'], $item['totalProfitLoss']);
        $summary['totalBalance'] = $this->addDec($summary['totalBalance'], $item['balance']);
        $summary['totalRebate'] = $this->addDec($summary['totalRebate'], $item['totalRebate']);
    }

    private function mapMoneyRow(array $row, array $keys): array
    {
        $out = [];
        foreach ($keys as $key) {
            $val = $row[$key] ?? null;
            if (in_array($key, [
                'balance', 'totalUp', 'totalDown', 'totalFlow', 'totalProfitLoss',
                'totalRebate', 'rebateRate',
            ], true)) {
                $out[$key] = $this->fmtDec($val);
            } else {
                $out[$key] = $val === null ? '' : (string) $val;
            }
        }
        return $out;
    }

    private function fmtDec(mixed $v): string
    {
        if ($v === null || $v === '') {
            return '0.0000';
        }
        return number_format((float) $v, 4, '.', '');
    }

    private function addDec(string $a, string $b): string
    {
        return number_format((float) $a + (float) $b, 4, '.', '');
    }
}
