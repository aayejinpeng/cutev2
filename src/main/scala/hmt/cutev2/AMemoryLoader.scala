
package boom.acc.cute

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config._
import boom.exu.ygjk._

//AMemoryLoader，用于加载A矩阵的数据，供给Scratchpad使用
//从不同的存储介质中加载数据，供给Scratchpad使用

//主要是从外部接口加载数据
//需要一个加速器整体的访存模块，接受MemoryLoader的请求，然后根据请求的地址，返回数据，MeomoryLoader发出虚拟地址
//这里其实涉及到一个比较隐蔽的问题，就是怎么设置这些页表来防止Linux的一些干扰，如SWAP、Lazy、CopyOnWrite等,这需要一系列的操作系统的支持
//本地的mmu会完成虚实地址转换，根据memoryloader的请求，选择从不同的存储介质中加载数据

//在本地最基础的是完成整体Tensor的加载，依据Scarchpad的设计，完成Tensor的切分以及将数据的填入Scaratchpad

//注意，数据的reorder是可以离线完成的！这也属于编译器的一环。

class ASourceIdSearch extends Bundle with HWParameters{
    val ScratchpadBankId = UInt(log2Ceil(AScratchpadNBanks).W)
    val ScratchpadAddr = UInt(log2Ceil(AScratchpadBankNEntrys).W)
}

class AMemoryLoader(implicit p: Parameters) extends Module with HWParameters{
    val io = IO(new Bundle{
        //先整一个ScarchPad的接口的总体设计
        val ToScarchPadIO = Flipped(new AMemoryLoaderScaratchpadIO)
        val ConfigInfo = Flipped(DecoupledIO(new ConfigInfoIO))
        val LocalMMUIO = Flipped(new LocalMMUIO)
        val MemoryLoadEnd = DecoupledIO(Bool())
        val TaskEnd = Flipped(DecoupledIO(Bool()))
    })
    //TODO:init
    io.ToScarchPadIO.BankAddr.valid := false.B
    io.ToScarchPadIO.BankAddr.bits := 0.U
    io.ToScarchPadIO.BankId.valid := false.B
    io.ToScarchPadIO.BankId.bits := 0.U
    io.ToScarchPadIO.Data.valid := false.B
    io.ToScarchPadIO.Data.bits := 0.U
    io.ToScarchPadIO.Chosen := false.B
    io.LocalMMUIO.Request.valid := false.B
    io.LocalMMUIO.Request.bits := DontCare
    io.ConfigInfo.ready := false.B
    io.MemoryLoadEnd.valid := false.B
    io.MemoryLoadEnd.bits := false.B
    io.TaskEnd.ready := false.B


    val ScaratchpadBankAddr = io.ToScarchPadIO.BankAddr
    val ScaratchpadData = io.ToScarchPadIO.Data
    val ScaratchpadChosen = io.ToScarchPadIO.Chosen

    val ConfigInfo = io.ConfigInfo.bits

    val ApplicationTensor_M = RegInit(0.U(ApplicationMaxTensorSizeBitSize.W))
    val ApplicationTensor_N = RegInit(0.U(ApplicationMaxTensorSizeBitSize.W))
    val ApplicationTensor_K = RegInit(0.U(ApplicationMaxTensorSizeBitSize.W))

    val Tensor_A_BaseVaddr = RegInit(0.U(MMUAddrWidth.W))
    // val Tensor_B_BaseVaddr = RegInit(0.U(MMUAddrWidth.W))
    // val Tensor_C_BaseVaddr = RegInit(0.U(MMUAddrWidth.W))

    
    //任务状态机 先来个简单的，顺序读取所有分块矩阵
    val s_idle :: s_mm_task :: s_end :: Nil = Enum(3) //TODO:新增状态，这里要加各种计算状态，mm，sliding window之类的
    val state = RegInit(s_idle)
    // printf("[AML]state:%d\n",state)

    //访存状态机，用来配合流水线刷新
    val s_load_idle :: s_load_init :: s_load_working :: s_load_end :: Nil = Enum(4)
    val memoryload_state = RegInit(s_load_idle)
    // printf("[AML]memoryload_state:%d\n",memoryload_state)
    val MemoryOrder_LoadConfig = RegInit(MemoryOrderType.OrderType_Mb_Kb) 

    val Tensor_Block_BaseAddr = Reg(UInt(MMUAddrWidth.W)) //分块矩阵的基地址

    val Conherent = RegInit(true.B) //是否一致性访存的标志位，由TaskController提供

    //TODO:设定内存中的数据排布，好让数据进行读取，reorder。目前最基础的，memory里的就和Scarchpad里的一样
    //TODO:读取的话，就每次读取256bit对齐的数据，然后塞到指定bank的指定addr上。
    //TODO:Taskcontroller负责对数据切块，并提供MemoryLoader的配置信息，起始位置，大小，数据排布等等
    
    //如果configinfo有效
    io.ConfigInfo.ready := false.B //TODO:
    when(io.ConfigInfo.valid){
        when(io.ConfigInfo.bits.taskType === CUTETaskType.TaskTypeMatrixMul){
            when(state === s_idle){
                state := s_mm_task
                memoryload_state := s_load_init
                ApplicationTensor_M := io.ConfigInfo.bits.ApplicationTensor_M
                ApplicationTensor_N := io.ConfigInfo.bits.ApplicationTensor_N
                ApplicationTensor_K := io.ConfigInfo.bits.ApplicationTensor_K
                Tensor_A_BaseVaddr := io.ConfigInfo.bits.ApplicationTensor_A.ApplicationTensor_A_BaseVaddr
                MemoryOrder_LoadConfig := io.ConfigInfo.bits.ApplicationTensor_A.MemoryOrder
                Tensor_Block_BaseAddr := io.ConfigInfo.bits.ApplicationTensor_A.BlockTensor_A_BaseVaddr
                Conherent := io.ConfigInfo.bits.ApplicationTensor_A.Conherent
                io.ConfigInfo.ready := true.B
            }
        }
    }

    //三个张量的虚拟地址，肯定得是连续的，这个可以交给操作系统和编译器来保证

    //A的数据已经完成了reorder
    //32×32×4B的数据    --->    一个4K页
    //32×128×1B的数据   --->    一个4K页
    //64×64×1B的数据    --->    一个4K页

    //页面内数据怎么排好像也无所谓，只要数据对齐且数据连续的就行了
    //这里的数据排布、更多的是为了memory连续读取时的性能考虑
    //那最好把单次读取的数据，都先放在一个页内不去连续的处理N个页？
    //那首先，每次连续读取的Tensor的数据是   AScartchpad = Tensor_M×Tensor_K×DataType = 64×64×1B = 4K
    //                                  BSctatchpad = Tensor_K×Tensor_N×DataType = 64×64×1B = 4K
    //                                  CScartchpad = Tensor_M×Tensor_N×DataType = 64×64×4B = 16K


    //这里的Scaratchpad，有可以节省大小的方案，就是尽可能早的去标记某个数据是无效的，然后对下一个数据发出请求，这样对SRAM的读写端口数量要求就高了，多读写端口vsdoublebufferSRAM
    //LLC的访存带宽我们设定成和每个bank的每个entry的大小一样。

    //处理取数逻辑，AScartchpad的数据大概率是LLC内的数据，所以我们可以直接从LLC中取数
    //如果是memoryload_state === s_load_init，那么我们就要初始化各个寄存器
    //如果是memoryload_state === s_load_working，那么我们就要开始取数
    //如果是memoryload_state === s_load_end，那么我们就要结束取数
    val TotalLoadSize = RegInit(0.U((log2Ceil(Tensor_M*Tensor_K)).W)) //总共要加载的张量大小，总加载的数据量是Tensor_M*Tensor_K*ruduceWidthByte，这个是不会变的
    val CurrentLoaded_BlockTensor_M = RegInit(0.U(ScaratchpadMaxTensorDimBitSize.W))
    val CurrentLoaded_BlockTensor_K = RegInit(0.U(ScaratchpadMaxTensorDimBitSize.W))
    
    //一个cam来存储访存请求的source_id对应的Scarchpad的地址和bank号
    //用sourceid做索引，存储Scarchpad的地址和bank号，是一组寄存器
    
    val SoureceIdSearchTable = RegInit(VecInit(Seq.fill(SoureceMaxNum)(0.U((new ASourceIdSearch).getWidth.W))))

    
    val Request = io.LocalMMUIO.Request
    Request.valid := false.B
    when(memoryload_state === s_load_init){
        memoryload_state := s_load_working
        TotalLoadSize := 0.U
        CurrentLoaded_BlockTensor_M := 0.U
        CurrentLoaded_BlockTensor_K := 0.U
    }.elsewhen(memoryload_state === s_load_working){
        //根据不同的MemoryOrder，执行不同的访存模式
        when(MemoryOrder_LoadConfig === MemoryOrderType.OrderType_Mb_Kb)
        {
            //只要Request是ready，我们发出的访存请求就会被MMU送往总线，我们可以发出下一个访存请求
            //TODO:注意所有的乘法电路的代价，和EDA存在的优化的可能性
            //担心乘法电路延迟，可以提前几个周期将乘法结果算好
            //TODO:注意这里的分块逻辑/地址拼接的逻辑，我们在设计MemoryOrderType分块的逻辑时，要考虑到这里的求地址的电路逻辑，是可以减少这部分的乘法电路的逻辑的
            Request.bits.RequestVirtualAddr := Tensor_Block_BaseAddr + (CurrentLoaded_BlockTensor_M * Tensor_K.U + CurrentLoaded_BlockTensor_K) * (ReduceWidthByte.U)
            //TODO:nonConherent的设计，需要让edge支持burst的访存请求，这个估计需要从L2Cache里抄
            
            val sourceId = Mux(Conherent,io.LocalMMUIO.ConherentRequsetSourceID,io.LocalMMUIO.nonConherentRequsetSourceID)
            Request.bits.RequestConherent := Conherent
            Request.bits.RequestSourceID := sourceId.bits
            Request.bits.RequestType_isWrite := false.B
            Request.valid := true.B
            when(MemoryOrder_LoadConfig === MemoryOrderType.OrderType_Mb_Kb && CurrentLoaded_BlockTensor_M === Tensor_M.U)
            {
                Request.valid := false.B
            }


            //数据在Scarachpad中的编排
            //数据会先排K，再排M
            //AVector一定是不同M的数据，K不断送入，直到K迭代完成，再换新的M，
            //   K 0 1 2 3 4 5 6 7     time     AVector     ScaratchpadData也这么排布
            // M                        0       0 8 g o             {bank[0] [1] [2] [3]}
            // 0   0 1 2 3 4 5 6 7      1       1 9 h p   |addr    0 |    0   8   g   o
            // 1   8 9 a b c d e f      2       2 a i q   |        1 |    1   9   h   p
            // 2   g h i j k l m n      3       3 b j r   |        2 |    2   a   i   q
            // 3   o p g r s t u v      4       4 c k s   |        3 |    3   b   j   r
            // 4   w x y z .......      5       5 d l t   |        4 |    4   c   k   s
            // 5   !..............      6       6 e m u   |        5 |    5   d   l   t
            // 6   @..............      7       7 f n v   |        6 |    6   e   m   u
            // 7   #..............      8       w ! @ #   |        7 |    7   f   n   v
            // 8   $..............      9       .......   | ...........................
            //
            //
            // 在内存中的排布则是 0 1 2 3 4 5 6 7 8 9 a b c d e f g h i j k l m n o p q r s t u v w x y z .......

            when(Request.ready && sourceId.valid){
                val TableItem = Wire(new ASourceIdSearch)
                TableItem.ScratchpadBankId := CurrentLoaded_BlockTensor_M % AScratchpadNBanks.U
                TableItem.ScratchpadAddr := ((CurrentLoaded_BlockTensor_M / AScratchpadNBanks.U) * Tensor_K.U) + CurrentLoaded_BlockTensor_K
                SoureceIdSearchTable(sourceId.bits) := TableItem.asUInt
                // printf("[AML]sourceId:%d,ScratchpadBankId:%d,ScratchpadAddr:%d\n",sourceId.bits,TableItem.ScratchpadBankId,TableItem.ScratchpadAddr)
                //输出这次request的信息
                // printf("[AML]RequestVirtualAddr:%x,RequestConherent:%d,RequestSourceID:%d,RequestType_isWrite:%d\n",Request.bits.RequestVirtualAddr,Request.bits.RequestConherent,Request.bits.RequestSourceID,Request.bits.RequestType_isWrite)
                when(MemoryOrder_LoadConfig === MemoryOrderType.OrderType_Mb_Kb){
                    //只要这条取数指令可以被发出，就计算下一个访存请求的地址
                    //TODO:这里数据读取量定死了，需要为了支持边界情况，改一改
                    //不过我们保证了数据是256bit对齐的～剩下的就是Tensor_M和Tensor_K不满足的情况思考好就行了
                    val MaxBlockTensor_M_Index = Tensor_M
                    val MaxBlockTensor_K_Index = Tensor_K
                    when(CurrentLoaded_BlockTensor_M < MaxBlockTensor_M_Index.U){
                        when(CurrentLoaded_BlockTensor_K < MaxBlockTensor_K_Index.U - 1.U){
                            //根据不同的内存Order，计算出访存请求的地址
                            CurrentLoaded_BlockTensor_K := CurrentLoaded_BlockTensor_K + 1.U
                        }.otherwise{
                            CurrentLoaded_BlockTensor_K := 0.U
                            CurrentLoaded_BlockTensor_M := CurrentLoaded_BlockTensor_M + 1.U
                        }
                    }
                }
            }
        }
        //接受访存的返回值
        //一个cam来存储访存请求的source_id对应的Scarchpad的地址和bank号
        //根据response的sourceid，找到对应的Scarchpad的地址和bank号，回填数据
        when(io.LocalMMUIO.Response.valid){
            //Trick注意这个设计，是doublebuffer的，AB只能是doublebuffer，回数一定是不会堵的，而且我们有时间对数据进行压缩解压缩～
            //如果要做release设计，要么数据位宽翻倍，腾出周期来使得有空泡能给写任务进行，要么就是数据位宽不变，将读写端口变成独立的读和独立的写端口
            TotalLoadSize := TotalLoadSize + 1.U
            val sourceId = io.LocalMMUIO.Response.bits.ReseponseSourceID
            val ScratchpadBankId = SoureceIdSearchTable(sourceId).asTypeOf(new ASourceIdSearch).ScratchpadBankId
            val ScratchpadAddr = SoureceIdSearchTable(sourceId).asTypeOf(new ASourceIdSearch).ScratchpadAddr
            val ResponseData = io.LocalMMUIO.Response.bits.ReseponseData
            //需要一个fifo？TODO:需要fifo的设计是可能这里会堵，实际上我们满吞吐的doublebuff的设计，咱们这里是不会堵的，直接填就完事了？还是等总线上去握手？
            //Scartchpad->MemoryLoader->MMU->Memory Bus->Memory上的长组合逻辑链，可以实现一下，为后续的开发做准备
            //否则就靠软件来保证数据流和访存流，保证访存流的稳定性，一定不会堵，就可以省下这个长组合逻辑的延迟？
            //还有一点，我们的ScartchPad是写优先的呀！！！所以只要写端口数唯一，就不会堵，不需要fifo～～～
            //Trick:写优先是真的很有说法，本来外部存储就是慢的，读快速存储器的任务等一等就好了，但是所有的ScartchPad都想要读数据的，不能等，所以写优先
            
            //根据response的的id
            io.ToScarchPadIO.BankAddr.bits := ScratchpadAddr
            io.ToScarchPadIO.BankId.bits := ScratchpadBankId
            io.ToScarchPadIO.Data.bits := ResponseData
            io.ToScarchPadIO.BankAddr.valid := true.B
            io.ToScarchPadIO.BankId.valid := true.B
            io.ToScarchPadIO.Data.valid := true.B
            //TODO:这里数据读取量定死了，需要为了支持边界情况，改一改
            when(TotalLoadSize === (Tensor_M * Tensor_K - 1).U){
                memoryload_state := s_load_end
            }
            //输出这次response的信息
            printf("[AML]ResponseData:%x,ScratchpadBankId:%d,ScratchpadAddr:%d\n",ResponseData,ScratchpadBankId,ScratchpadAddr)
            //response的sourceid
            printf("[AML]ResponseSourceID:%d\n",sourceId)
            //输出这次的totalload
            printf("[AML]TotalLoadSize:%d\n",TotalLoadSize)
        }
        
    }.elsewhen(memoryload_state === s_load_end){
        io.MemoryLoadEnd.bits := true.B
        io.MemoryLoadEnd.valid := true.B
        when(io.MemoryLoadEnd.fire){
            memoryload_state := s_load_idle
        }
    }.otherwise{
        memoryload_state := s_load_idle
        io.ConfigInfo.ready := true.B
        when(io.ConfigInfo.fire){
            when(io.ConfigInfo.bits.taskType === CUTETaskType.TaskTypeMatrixMul){
                when(state === s_idle){
                    memoryload_state := s_load_init
                }
            }
        }
    }

}