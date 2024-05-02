wrk.method = "GET"

math.randomseed(os.time())

request = function()
    path = "/v0/entities?start=0"
    return wrk.format(nil, path)
end
