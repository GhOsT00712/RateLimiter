-- KEYS[1] = bucket key
-- ARGV[1] = capacity, ARGV[2] = refillPerSec, ARGV[3] = tokens requested
local capacity = tonumber(ARGV[1])
local rate     = tonumber(ARGV[2])
local want     = tonumber(ARGV[3])

local t   = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)

local b      = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(b[1]) or capacity      -- new bucket starts full
local ts     = tonumber(b[2]) or now

tokens = math.min(capacity, tokens + math.max(0, now - ts) / 1000 * rate)

local granted = math.min(want, math.floor(tokens))
tokens = tokens - granted

redis.call('HSET', KEYS[1], 'tokens', string.format('%.6f', tokens), 'ts', string.format('%d', now))
redis.call('PEXPIRE', KEYS[1], string.format('%d', math.ceil(capacity / rate * 1000)))

local retryMs = 0
if granted == 0 then retryMs = math.ceil((1 - tokens) / rate * 1000) end
return { granted, math.floor(tokens), retryMs }
