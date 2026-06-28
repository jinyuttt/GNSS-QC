# -*- coding: utf-8 -*-
"""存储抽象接口"""

from abc import ABC, abstractmethod
from typing import List, Optional, Dict, Any


class StorageProvider(ABC):
    """存储提供者抽象基类"""

    @abstractmethod
    def save_raw_data(self, station_id: str, epoch_millis: int,
                      features: Dict[str, Any]) -> bool:
        """保存原始基准数据"""
        pass

    @abstractmethod
    def save_eval_result(self, station_id: str, epoch_millis: int,
                         result: Dict[str, Any]) -> bool:
        """保存评测结果"""
        pass

    @abstractmethod
    def query_results(self, station_id: str,
                      limit: int = 100) -> List[Dict[str, Any]]:
        """查询影子评测结果"""
        pass

    @abstractmethod
    def query_raw_data(self, station_id: str,
                       start_time: int = 0, end_time: int = 0,
                       limit: int = 10000) -> List[Dict[str, Any]]:
        """查询原始数据"""
        pass

    @abstractmethod
    def get_training_samples(self, start_time: int, end_time: int,
                             limit: int = 10000) -> List[Dict[str, Any]]:
        """获取训练样本"""
        pass

    @abstractmethod
    def cleanup_expired(self) -> int:
        """清理过期数据，返回清理条数"""
        pass

    @abstractmethod
    def is_available(self) -> bool:
        """检查存储是否可用"""
        pass

    @abstractmethod
    def get_stats(self) -> Dict[str, Any]:
        """获取存储统计信息"""
        pass