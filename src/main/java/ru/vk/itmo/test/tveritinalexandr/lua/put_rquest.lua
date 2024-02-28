function create_random_value(min_length, max_length)
    local charset = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    local random_string = ""
    local charset_length = string.len(charset)

    math.randomseed(os.time())

    local length = math.random(min_length, max_length)

    for i = 1, length do
        local random_index = math.random(1, charset_length)
        random_string = random_string .. string.sub(charset, random_index, random_index)
    end

    return random_string
end

request = function()
    local headers = {}
    headers["Host"] = "localhost"
    local id = math.random(1, 2000)
    local body = create_random_value(20, 100)
    return wrk.format("PUT", "/v0/entity?id=" .. id, headers, body)
end