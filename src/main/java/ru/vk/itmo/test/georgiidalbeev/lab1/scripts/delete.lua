math.randomseed(os.time())

request = function()
    local headers = {}
    headers["Host"] = "localhost"
    local id = math.random(1, 1000)
    return wrk.format("DELETE", "/v0/entity?id=" .. id, headers)
end