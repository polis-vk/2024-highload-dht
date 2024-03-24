function request()
    path = "/v0/entity?id=" .. math.random(0, 50000)
    wrk.method = "PUT"
    wrk.body = randomString()
    return wrk.format(nil, path)
end

function randomString()
	local length = math.random(10,100)
	local array = {}
	for i = 1, length do
		array[i] = string.char(math.random(55, 123))
	end
	return table.concat(array)
end
