math.randomseed(os.time())

request = function()
    local headers = {}
    headers["Host"] = "localhost"
    local id = math.random(1, 1000000)
    return wrk.format("GET", "/v0/entity?id=" .. id, headers)
end