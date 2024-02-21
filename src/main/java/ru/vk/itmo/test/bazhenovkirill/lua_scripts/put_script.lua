math.randomseed(os.time())
path = "/v0/entity?id="

function generate_random_value(length)
    local res = ""
    for _ = 1, length do
        res = res .. string.char(math.random(97, 122))
    end
    return res
end

function generate_random_key()
    return "key" .. math.random(1, 350000000)
end

function request()
    url = path .. generate_random_key()
    value = generate_random_value(5)
    headers = {}
    headers["Host"] = "localhost:8080"
    return wrk.format("PUT", url, headers, value)
end