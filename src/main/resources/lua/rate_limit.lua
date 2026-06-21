local key = KEYS[1]
local maxCount = tonumber(ARGV[1])
local seconds = tonumber(ARGV[2])

local num = redis.call('GET', key)
if num then
    if tonumber(num) >= maxCount then
        return 0
    else
        redis.call('INCR', key)
    end
else
    redis.call('SET', key, 1, 'EX', seconds)
end
return 1