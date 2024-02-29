math.randomseed(os.time())

function generate()
    return math.random(0, 2031000)
end

wrk.method = "GET"

request = function()
   path = "/v0/entity?id=" .. generate()
   return wrk.format(nil, path)
end
