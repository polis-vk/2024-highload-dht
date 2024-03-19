math.randomseed(os.time())

wrk.host = "localhost"
wrk.port = "8080"

function request()
    local id = tostring(math.random(0, 1000000))
    local path = "/v0/entity?id=" .. id
    return wrk.format("GET", path)
end
